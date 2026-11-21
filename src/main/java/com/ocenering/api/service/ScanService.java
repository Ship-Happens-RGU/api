package com.ocenering.api.service;

import com.ocenering.api.auth.AuthenticatedUser;
import com.ocenering.api.iceberg.ScanCatalog;
import com.ocenering.api.iceberg.ScanStorageException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.GenericAppenderFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.encryption.EncryptedFiles;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.PositionOutputStream;
import org.springframework.stereotype.Service;

/**
 * Scans stored in Iceberg. Each upload writes the raw point-cloud file to S3 under the table's
 * location and appends one row (one Avro data file, one snapshot) describing it.
 */
@Service
public class ScanService {

    public record Scan(
            UUID id,
            UUID treeId,
            String name,
            OffsetDateTime scanDate,
            String fileName,
            long fileSize,
            String notes,
            String uploadedBy,
            OffsetDateTime uploadedAt,
            ScanModelService.ModelInfo model) {

        Scan withModel(ScanModelService.ModelInfo m) {
            return new Scan(id, treeId, name, scanDate, fileName, fileSize, notes, uploadedBy, uploadedAt, m);
        }
    }

    /** Per-tree aggregates for the folder list. */
    public record TreeStats(int scanCount, OffsetDateTime firstScanDate, OffsetDateTime lastScanDate) {
    }

    /** Metadata and S3 input handle for streaming a scan without redundant catalog lookups. */
    public record DownloadableScan(Scan scan, InputFile file) {
    }

    @FunctionalInterface
    public interface UploadProgressListener {
        void onProgress(String phase, String message, double fraction, long writtenBytes, long totalBytes);
    }

    private record StoredScan(Scan scan, String location) {
    }

    private final ScanCatalog scanCatalog;

    private final Map<UUID, StoredScan> scanCache = new ConcurrentHashMap<>();

    private final ScanModelService scanModels;

    private volatile FileIO cachedIo;

    public ScanService(ScanCatalog scanCatalog, ScanModelService scanModels) {
        this.scanCatalog = scanCatalog;
        this.scanModels = scanModels;
    }

    /** All scans of a tree, oldest scan date first (timeline order). */
    public List<Scan> listByTree(UUID treeId) {
        List<Scan> out = new ArrayList<>();

        for (StoredScan s : read(scanCatalog.table(), Expressions.equal("tree_id", treeId.toString()))) {
            if (s.scan().treeId().equals(treeId)) {
                out.add(s.scan());
            }
        }

        out.sort(Comparator.comparing(Scan::scanDate).thenComparing(Scan::uploadedAt));

        Map<UUID, ScanModelService.ModelInfo> models = scanModels.infoFor(out.stream().map(Scan::id).toList());
        out.replaceAll(s -> s.withModel(models.get(s.id())));

        return out;
    }

    public Map<UUID, TreeStats> statsByTree() {
        Map<UUID, TreeStats> stats = new HashMap<>();

        for (StoredScan s : read(scanCatalog.table(), Expressions.alwaysTrue())) {
            Scan scan = s.scan();
            stats.merge(scan.treeId(), new TreeStats(1, scan.scanDate(), scan.scanDate()), (a, b) -> new TreeStats(
                    a.scanCount() + b.scanCount(),
                    a.firstScanDate().isBefore(b.firstScanDate()) ? a.firstScanDate() : b.firstScanDate(),
                    a.lastScanDate().isAfter(b.lastScanDate()) ? a.lastScanDate() : b.lastScanDate()));
        }

        return stats;
    }

    public Optional<Scan> find(UUID scanId) {
        StoredScan cached = scanCache.get(scanId);

        Optional<Scan> scan = cached != null
                ? Optional.of(cached.scan())
                : findStored(scanCatalog.table(), scanId).map(StoredScan::scan);

        return scan.map(s -> s.withModel(scanModels.info(scanId).orElse(null)));
    }

    /** Streams the scan's raw file to S3, then commits its row. The file is removed if the commit fails. */
    public Scan upload(UUID treeId, String name, OffsetDateTime scanDate, String notes,
                       String originalFileName, long size, InputStream content, AuthenticatedUser me) {
        return upload(treeId, name, scanDate, notes, originalFileName, size, content, me, null);
    }

    public Scan upload(UUID treeId, String name, OffsetDateTime scanDate, String notes,
                       String originalFileName, long size, InputStream content, AuthenticatedUser me,
                       UploadProgressListener listener) {
        if (listener != null) {
            listener.onProgress("processing", "Processing scan in Java…", 0.05, 0, size);
        }

        Table table = scanCatalog.table();
        cachedIo = table.io();
        UUID scanId = UUID.randomUUID();
        String fileName = sanitizeFileName(originalFileName);
        String location = trimSlash(table.location()) + "/files/" + treeId + "/" + scanId + "/" + fileName;

        long written;

        try {
            written = writeObject(table, location, content, size, listener);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(table, location);

            throw new ScanStorageException("Uploading the scan file to S3 failed: " + e.getMessage(), e);
        }

        if (listener != null) {
            listener.onProgress("iceberg_commit", "Committing metadata to Apache Iceberg…", 0.95, written, written);
        }

        Scan scan = new Scan(scanId, treeId, name, scanDate.withOffsetSameInstant(ZoneOffset.UTC), fileName,
                written > 0 ? written : size, blankToNull(notes), me != null ? me.email() : null,
                OffsetDateTime.now(ZoneOffset.UTC), null);

        try {
            appendRow(table, toRecord(table, scan, location));
        } catch (IOException | RuntimeException e) {
            deleteQuietly(table, location);

            throw new ScanStorageException("Committing the scan to Iceberg failed: " + e.getMessage(), e);
        }

        scanCache.put(scanId, new StoredScan(scan, location));

        if (listener != null) {
            listener.onProgress("complete", "Upload complete!", 1.0, written, written);
        }

        return scan;
    }

    /** Opens the stored point-cloud file for streaming back to the client. */
    public Optional<InputFile> openFile(UUID scanId) {
        return openDownload(scanId).map(DownloadableScan::file);
    }

    /**
     * Resolves both the scan metadata and S3 InputFile using an in-memory cache so HTTP Range
     * requests do not repeatedly scan Iceberg manifests or issue S3 HeadObject calls.
     */
    public Optional<DownloadableScan> openDownload(UUID scanId) {
        StoredScan stored = scanCache.get(scanId);
        FileIO io = cachedIo;

        if (stored == null || io == null) {
            Table table = scanCatalog.table();
            io = table.io();
            cachedIo = io;

            if (stored == null) {
                stored = findStored(table, scanId).orElse(null);
            }
        }

        if (stored == null) {
            return Optional.empty();
        }

        long fileSize = stored.scan().fileSize();
        InputFile inputFile = fileSize > 0
                ? io.newInputFile(stored.location(), fileSize)
                : io.newInputFile(stored.location());

        return Optional.of(new DownloadableScan(stored.scan(), inputFile));
    }

    public boolean delete(UUID scanId) {
        Table table = scanCatalog.table();
        Optional<StoredScan> stored = findStored(table, scanId);

        if (stored.isEmpty()) {
            return false;
        }

        table.newDelete().deleteFromRowFilter(Expressions.equal("scan_id", scanId.toString())).commit();
        scanCache.remove(scanId);
        deleteQuietly(table, stored.get().location());
        scanModels.delete(scanId);

        return true;
    }

    /** Removes every scan in a tree (a whole partition) and their files. */
    public void deleteTree(UUID treeId) {
        scanModels.deleteTree(treeId);
        Table table = scanCatalog.table();
        List<StoredScan> stored = read(table, Expressions.equal("tree_id", treeId.toString()));

        if (stored.isEmpty()) {
            return;
        }

        table.newDelete().deleteFromRowFilter(Expressions.equal("tree_id", treeId.toString())).commit();

        for (StoredScan s : stored) {
            scanCache.remove(s.scan().id());
            deleteQuietly(table, s.location());
        }
    }

    private Optional<StoredScan> findStored(Table table, UUID scanId) {
        return read(table, Expressions.equal("scan_id", scanId.toString())).stream()
                .filter(s -> s.scan().id().equals(scanId))
                .findFirst();
    }

    private List<StoredScan> read(Table table, Expression filter) {
        cachedIo = table.io();
        List<StoredScan> out = new ArrayList<>();

        try (CloseableIterable<Record> rows = IcebergGenerics.read(table).where(filter).build()) {
            for (Record r : rows) {
                StoredScan stored = fromRecord(r);
                scanCache.put(stored.scan().id(), stored);
                out.add(stored);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return out;
    }

    private static long writeObject(Table table, String location, InputStream content, long expectedSize,
                                    UploadProgressListener listener) throws IOException {
        OutputFile out = table.io().newOutputFile(location);

        try (InputStream in = content; PositionOutputStream os = out.createOrOverwrite()) {
            byte[] buffer = new byte[4 * 1024 * 1024];
            long total = 0;
            long lastReport = System.currentTimeMillis();
            int read;

            while ((read = in.read(buffer)) >= 0) {
                os.write(buffer, 0, read);
                total += read;

                long now = System.currentTimeMillis();

                if (listener != null && now - lastReport >= 250) {
                    double fraction = expectedSize > 0 ? (double) total / expectedSize : 0;
                    listener.onProgress("s3_upload", "Uploading from Java to S3 storage…",
                            Math.min(0.92, 0.05 + fraction * 0.87), total, expectedSize);
                    lastReport = now;
                }
            }

            if (listener != null) {
                listener.onProgress("s3_upload", "Finalizing S3 upload…", 0.92, total, expectedSize);
            }

            return os.getPos();
        }
    }

    private static void appendRow(Table table, Record row) throws IOException {
        PartitionKey key = new PartitionKey(table.spec(), table.schema());
        key.partition(row);

        String path = table.locationProvider()
                .newDataLocation(table.spec(), key, "scan-" + UUID.randomUUID() + ".avro");
        GenericAppenderFactory factory = new GenericAppenderFactory(table.schema(), table.spec());
        DataWriter<Record> writer = factory.newDataWriter(
                EncryptedFiles.plainAsEncryptedOutput(table.io().newOutputFile(path)), FileFormat.AVRO, key);

        try (writer) {
            writer.write(row);
        }

        table.newAppend().appendFile(writer.toDataFile()).commit();
    }

    private static Record toRecord(Table table, Scan scan, String location) {
        GenericRecord r = GenericRecord.create(table.schema());
        r.setField("scan_id", scan.id().toString());
        r.setField("tree_id", scan.treeId().toString());
        r.setField("name", scan.name());
        r.setField("scan_date", scan.scanDate());
        r.setField("file_name", scan.fileName());
        r.setField("file_size", scan.fileSize());
        r.setField("file_location", location);
        r.setField("notes", scan.notes());
        r.setField("uploaded_by", scan.uploadedBy());
        r.setField("uploaded_at", scan.uploadedAt());

        return r;
    }

    private static StoredScan fromRecord(Record r) {
        Scan scan = new Scan(
                UUID.fromString(r.getField("scan_id").toString()),
                UUID.fromString(r.getField("tree_id").toString()),
                str(r.getField("name")),
                (OffsetDateTime) r.getField("scan_date"),
                str(r.getField("file_name")),
                ((Number) r.getField("file_size")).longValue(),
                str(r.getField("notes")),
                str(r.getField("uploaded_by")),
                (OffsetDateTime) r.getField("uploaded_at"),
                null);

        return new StoredScan(scan, str(r.getField("file_location")));
    }

    private static void deleteQuietly(Table table, String location) {
        try {
            table.io().deleteFile(location);
        } catch (RuntimeException ignored) {
            // best effort: an orphaned object is harmless
        }
    }

    private static String sanitizeFileName(String name) {
        String base = name == null ? "" : name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");

        return base.isBlank() || base.chars().allMatch(c -> c == '.') ? "scan.bin" : base;
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}

