package com.ocenering.api.iceberg;

import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * The Iceberg table that holds every scan. One row per scan, partitioned by scan tree; the scan's
 * point-cloud file sits next to the table in S3 and the row points at it.
 *
 * <p>The catalog is connected lazily so the API still boots when Iceberg/LocalStack are down; scan
 * endpoints then answer 503 until it comes back.
 */
@Component
public class ScanCatalog implements DisposableBean {

    public static final Schema SCHEMA = new Schema(
            Types.NestedField.required(1, "scan_id", Types.StringType.get()),
            Types.NestedField.required(2, "tree_id", Types.StringType.get()),
            Types.NestedField.required(3, "name", Types.StringType.get()),
            Types.NestedField.required(4, "scan_date", Types.TimestampType.withZone()),
            Types.NestedField.required(5, "file_name", Types.StringType.get()),
            Types.NestedField.required(6, "file_size", Types.LongType.get()),
            Types.NestedField.required(7, "file_location", Types.StringType.get()),
            Types.NestedField.optional(8, "notes", Types.StringType.get()),
            Types.NestedField.optional(9, "uploaded_by", Types.StringType.get()),
            Types.NestedField.required(10, "uploaded_at", Types.TimestampType.withZone()));

    public static final PartitionSpec SPEC = PartitionSpec.builderFor(SCHEMA).identity("tree_id").build();

    /** Full column metrics let a delete by scan_id be resolved from manifests alone. */
    private static final Map<String, String> TABLE_PROPERTIES = Map.of(
            TableProperties.FORMAT_VERSION, "2",
            TableProperties.DEFAULT_FILE_FORMAT, "avro",
            TableProperties.DEFAULT_WRITE_METRICS_MODE, "full");

    private static final Logger LOG = LoggerFactory.getLogger(ScanCatalog.class);

    private final IcebergProperties props;

    private RESTCatalog catalog;

    private TableIdentifier tableId;

    public ScanCatalog(IcebergProperties props) {
        this.props = props;
    }

    /** A freshly loaded handle on the scans table (so every call sees the latest snapshot). */
    public Table table() {
        try {
            RESTCatalog c = connect();

            return c.loadTable(tableId);
        } catch (RuntimeException e) {
            reset();

            throw new ScanStorageException("Scan storage (Iceberg) is unavailable: " + e.getMessage(), e);
        }
    }

    private synchronized RESTCatalog connect() {
        if (catalog != null) {
            return catalog;
        }

        Map<String, String> p = new HashMap<>();
        p.put(CatalogProperties.URI, props.catalogUri());
        p.put(CatalogProperties.WAREHOUSE_LOCATION, props.warehouse());
        p.put(CatalogProperties.FILE_IO_IMPL, "org.apache.iceberg.aws.s3.S3FileIO");
        p.put("client.region", props.region());
        p.put("s3.path-style-access", "true");

        if (props.s3Endpoint() != null && !props.s3Endpoint().isBlank()) {
            p.put("s3.endpoint", props.s3Endpoint());
        }

        if (props.s3AccessKey() != null && !props.s3AccessKey().isBlank()) {
            p.put("s3.access-key-id", props.s3AccessKey());
            p.put("s3.secret-access-key", props.s3SecretKey());
        }

        RESTCatalog c = new RESTCatalog();
        c.initialize("shiphappens", p);

        Namespace ns = Namespace.of(props.namespace());
        TableIdentifier id = TableIdentifier.of(ns, props.table());
        ensureTable(c, ns, id);

        catalog = c;
        tableId = id;
        LOG.info("Connected to Iceberg catalog {} (table {})", props.catalogUri(), id);

        return c;
    }

    private static void ensureTable(RESTCatalog c, Namespace ns, TableIdentifier id) {
        if (!c.namespaceExists(ns)) {
            try {
                c.createNamespace(ns);
            } catch (AlreadyExistsException ignored) {
                // created concurrently
            }
        }

        if (!c.tableExists(id)) {
            try {
                c.buildTable(id, SCHEMA)
                        .withPartitionSpec(SPEC)
                        .withProperties(TABLE_PROPERTIES)
                        .create();
                LOG.info("Created Iceberg table {}", id);

                return;
            } catch (AlreadyExistsException ignored) {
                // created concurrently
            }
        }

        Table existing = c.loadTable(id);

        if (!"full".equals(existing.properties().get(TableProperties.DEFAULT_WRITE_METRICS_MODE))) {
            existing.updateProperties().set(TableProperties.DEFAULT_WRITE_METRICS_MODE, "full").commit();
        }
    }

    private synchronized void reset() {
        closeQuietly();
        catalog = null;
    }

    private void closeQuietly() {
        if (catalog == null) {
            return;
        }

        try {
            catalog.close();
        } catch (Exception e) {
            LOG.debug("Closing Iceberg catalog failed", e);
        }
    }

    @Override
    public synchronized void destroy() {
        closeQuietly();
    }
}

