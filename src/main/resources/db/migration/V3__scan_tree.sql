-- A scan tree is a folder of scans. The scans themselves live in Iceberg (see ScanCatalog);
-- only the folder is relational.
CREATE TABLE platform.scan_tree (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text NOT NULL,
    description text,
    created_by  uuid REFERENCES platform.app_user(id) ON DELETE SET NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX scan_tree_name_idx ON platform.scan_tree (lower(name));

