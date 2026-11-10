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
            OffsetDateTime uploadedAt) {
    }

    /** Per-tree aggregates for the folder list. */
    public record TreeStats(int scanCount, OffsetDateTime firstScanDate, OffsetDateTime lastScanDate) {
    }

    private record StoredScan(Scan scan, String location) {
    }

    private final ScanCatalog scanCatalog;

    public ScanService(ScanCatalog scanCatalog) {
        this.scanCatalog = scanCatalog;
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
        return findStored(scanCatalog.table(), scanId).map(StoredScan::scan);
    }

    /** Streams the scan's raw file to S3, then commits its row. The file is removed if the commit fails. */
    public Scan upload(UUID treeId, String name, OffsetDateTime scanDate, String notes,
                       String originalFileName, long size, InputStream content, AuthenticatedUser me) {
        Table table = scanCatalog.table();
        UUID scanId = UUID.randomUUID();
        String fileName = sanitizeFileName(originalFileName);
        String location = trimSlash(table.location()) + "/files/" + treeId + "/" + scanId + "/" + fileName;

        long written;

        try {
            written = writeObject(table, location, content);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(table, location);

            throw new ScanStorageException("Uploading the scan file to S3 failed: " + e.getMessage(), e);
        }

        Scan scan = new Scan(scanId, treeId, name, scanDate.withOffsetSameInstant(ZoneOffset.UTC), fileName,
                written > 0 ? written : size, blankToNull(notes), me != null ? me.email() : null,
                OffsetDateTime.now(ZoneOffset.UTC));

        try {
            appendRow(table, toRecord(table, scan, location));
        } catch (IOException | RuntimeException e) {
            deleteQuietly(table, location);

            throw new ScanStorageException("Committing the scan to Iceberg failed: " + e.getMessage(), e);
        }

        return scan;
    }

    /** Opens the stored point-cloud file for streaming back to the client. */
    public Optional<InputFile> openFile(UUID scanId) {
        Table table = scanCatalog.table();

        return findStored(table, scanId).map(s -> table.io().newInputFile(s.location()));
    }

    public boolean delete(UUID scanId) {
        Table table = scanCatalog.table();
        Optional<StoredScan> stored = findStored(table, scanId);

        if (stored.isEmpty()) {
            return false;
        }

        table.newDelete().deleteFromRowFilter(Expressions.equal("scan_id", scanId.toString())).commit();
        deleteQuietly(table, stored.get().location());

        return true;
    }

    /** Removes every scan in a tree (a whole partition) and their files. */
    public void deleteTree(UUID treeId) {
        Table table = scanCatalog.table();
        List<StoredScan> stored = read(table, Expressions.equal("tree_id", treeId.toString()));

        if (stored.isEmpty()) {
            return;
        }

        table.newDelete().deleteFromRowFilter(Expressions.equal("tree_id", treeId.toString())).commit();

        for (StoredScan s : stored) {
            deleteQuietly(table, s.location());
        }
    }

    private Optional<StoredScan> findStored(Table table, UUID scanId) {
        return read(table, Expressions.equal("scan_id", scanId.toString())).stream()
                .filter(s -> s.scan().id().equals(scanId))
                .findFirst();
    }

    private static List<StoredScan> read(Table table, Expression filter) {
        List<StoredScan> out = new ArrayList<>();

        try (CloseableIterable<Record> rows = IcebergGenerics.read(table).where(filter).build()) {
            for (Record r : rows) {
                out.add(fromRecord(r));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return out;
    }

    private static long writeObject(Table table, String location, InputStream content) throws IOException {
        OutputFile out = table.io().newOutputFile(location);

        try (InputStream in = content; PositionOutputStream os = out.createOrOverwrite()) {
            in.transferTo(os);

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
                (OffsetDateTime) r.getField("uploaded_at"));

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

