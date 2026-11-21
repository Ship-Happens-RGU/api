package com.ocenering.api.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A GLB mesh converted from a scan's point cloud. The file lives in S3 at {@code fileLocation}. */
@Entity
@Table(name = "scan_model", schema = "platform")
public class ScanModel {

    @Id
    @Column(name = "scan_id")
    private UUID scanId;

    @Column(name = "tree_id", nullable = false)
    private UUID treeId;

    @Column(name = "file_location", nullable = false)
    private String fileLocation;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(nullable = false)
    private String format = "glb";

    @Column(name = "vertex_count")
    private Long vertexCount;

    @Column(name = "triangle_count")
    private Long triangleCount;

    @Column(name = "voxel_cm")
    private Double voxelCm;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ScanModel() {
        // required by JPA
    }

    public ScanModel(UUID scanId, UUID treeId) {
        this.scanId = scanId;
        this.treeId = treeId;
    }

    public UUID getScanId() {
        return scanId;
    }

    public UUID getTreeId() {
        return treeId;
    }

    public String getFileLocation() {
        return fileLocation;
    }

    public void setFileLocation(String fileLocation) {
        this.fileLocation = fileLocation;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFormat() {
        return format;
    }

    public Long getVertexCount() {
        return vertexCount;
    }

    public void setVertexCount(Long vertexCount) {
        this.vertexCount = vertexCount;
    }

    public Long getTriangleCount() {
        return triangleCount;
    }

    public void setTriangleCount(Long triangleCount) {
        this.triangleCount = triangleCount;
    }

    public Double getVoxelCm() {
        return voxelCm;
    }

    public void setVoxelCm(Double voxelCm) {
        this.voxelCm = voxelCm;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
