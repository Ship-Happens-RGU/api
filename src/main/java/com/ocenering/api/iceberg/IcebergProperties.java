package com.ocenering.api.iceberg;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Where the Iceberg REST catalog and its S3 warehouse live. */
@ConfigurationProperties(prefix = "app.iceberg")
public record IcebergProperties(
        @DefaultValue("http://localhost:8181") String catalogUri,
        @DefaultValue("s3://warehouse/") String warehouse,
        @DefaultValue("shiphappens") String namespace,
        @DefaultValue("scans") String table,
        String s3Endpoint,
        String s3AccessKey,
        String s3SecretKey,
        @DefaultValue("us-east-1") String region) {
}

