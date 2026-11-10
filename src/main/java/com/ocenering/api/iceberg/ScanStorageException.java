package com.ocenering.api.iceberg;

/** Iceberg or its S3 warehouse could not be reached; surfaced to clients as 503. */
public class ScanStorageException extends RuntimeException {

    public ScanStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

