package com.ocenering.api.service;

import com.ocenering.api.db.entity.ScanModel;
import com.ocenering.api.db.repository.ScanModelRepository;
import com.ocenering.api.iceberg.ScanCatalog;
import com.ocenering.api.iceberg.ScanStorageException;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.iceberg.Table;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.PositionOutputStream;
import org.springframework.stereotype.Service;

/**
 * Mesh models (GLB) converted from scans. The browser converts the E57 after upload and sends the
 * model here; it is written to S3 beside the scan files and indexed in Postgres.
 */
@Service
public class ScanModelService {

    /** What the API says about a scan's model; null on a scan that has none yet. */
    public record ModelInfo(String format, long fileSize, Long vertexCount, Long triangleCount, Double voxelCm,
                            OffsetDateTime createdAt) {
    }

    /** Stats the converter reports alongside the file. */
    public record ModelStats(Long vertexCount, Long triangleCount, Double voxelCm) {
    }

    /** GLB magic: "glTF" little-endian. */
    private static final int GLB_MAGIC = 0x46546C67;

    private final ScanModelRepository models;

    private final ScanCatalog scanCatalog;

    public ScanModelService(ScanModelRepository models, ScanCatalog scanCatalog) {
        this.models = models;
        this.scanCatalog = scanCatalog;
    }

    public Optional<ModelInfo> info(UUID scanId) {
        return models.findById(scanId).map(ScanModelService::toInfo);
    }

    public Map<UUID, ModelInfo> infoFor(Collection<UUID> scanIds) {
        Map<UUID, ModelInfo> out = new HashMap<>();

        for (ScanModel m : models.findAllById(scanIds)) {
            out.put(m.getScanId(), toInfo(m));
        }

        return out;
    }

    /** Writes (or replaces) the scan's model file and its row. */
    public ModelInfo store(UUID scanId, UUID treeId, InputStream content, ModelStats stats, String uploadedBy) {
        Table table = scanCatalog.table();
        String location = trimSlash(table.location()) + "/models/" + treeId + "/" + scanId + "/model.glb";
        long written;

        try {
            written = writeGlb(table, location, content);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(table, location);

            throw new ScanStorageException("Uploading the model to S3 failed: " + e.getMessage(), e);
        }

        ScanModel row = models.findById(scanId).orElseGet(() -> new ScanModel(scanId, treeId));
        row.setFileLocation(location);
        row.setFileSize(written);
        row.setVertexCount(stats.vertexCount());
        row.setTriangleCount(stats.triangleCount());
        row.setVoxelCm(stats.voxelCm());
        row.setCreatedBy(uploadedBy);
        row.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        models.save(row);

        return toInfo(row);
    }

    /** The stored model's metadata and an S3 handle to stream it. */
    public Optional<Map.Entry<ModelInfo, InputFile>> open(UUID scanId) {
        return models.findById(scanId).map(m -> Map.entry(toInfo(m),
                scanCatalog.table().io().newInputFile(m.getFileLocation(), m.getFileSize())));
    }

    public boolean delete(UUID scanId) {
        Optional<ScanModel> row = models.findById(scanId);

        if (row.isEmpty()) {
            return false;
        }

        models.delete(row.get());
        deleteQuietly(scanCatalog.table(), row.get().getFileLocation());

        return true;
    }

    public void deleteTree(UUID treeId) {
        List<ScanModel> rows = models.findAllByTreeId(treeId);

        if (rows.isEmpty()) {
            return;
        }

        Table table = scanCatalog.table();
        models.deleteAll(rows);

        for (ScanModel m : rows) {
            deleteQuietly(table, m.getFileLocation());
        }
    }

    /** Streams the upload to S3, refusing anything that does not start with a GLB header. */
    private static long writeGlb(Table table, String location, InputStream content) throws IOException {
        OutputFile out = table.io().newOutputFile(location);

        try (InputStream in = content; PositionOutputStream os = out.createOrOverwrite()) {
            byte[] head = in.readNBytes(12);

            if (head.length < 12 || readIntLe(head, 0) != GLB_MAGIC || readIntLe(head, 4) != 2) {
                throw new IllegalArgumentException("the model is not a glTF 2.0 binary (.glb) file");
            }

            os.write(head);
            in.transferTo(os);

            return os.getPos();
        }
    }

    private static int readIntLe(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    private static ModelInfo toInfo(ScanModel m) {
        return new ModelInfo(m.getFormat(), m.getFileSize(), m.getVertexCount(), m.getTriangleCount(),
                m.getVoxelCm(), m.getCreatedAt());
    }

    private static void deleteQuietly(Table table, String location) {
        try {
            table.io().deleteFile(location);
        } catch (RuntimeException ignored) {
            // best effort: an orphaned object is harmless
        }
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
