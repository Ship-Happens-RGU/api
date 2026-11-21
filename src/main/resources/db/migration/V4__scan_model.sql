-- A triangle-mesh model (GLB) derived from a scan's point cloud. The scan itself lives in Iceberg;
-- the model file sits in S3 next to it and this row says where, so the viewer can load the model
-- first and fall back to the point cloud.
CREATE TABLE platform.scan_model (
    scan_id        uuid PRIMARY KEY,
    tree_id        uuid NOT NULL,
    file_location  text NOT NULL,
    file_size      bigint NOT NULL,
    format         text NOT NULL DEFAULT 'glb',
    vertex_count   bigint,
    triangle_count bigint,
    voxel_cm       double precision,
    created_by     text,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX scan_model_tree_idx ON platform.scan_model (tree_id);
