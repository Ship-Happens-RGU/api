package com.ocenering.api.controller.v1;

import com.ocenering.api.iceberg.ScanStorageException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Turns scan errors into the { "error": "..." } shape the web client already reads. */
@RestControllerAdvice(assignableTypes = {ScanTreeController.class, ScanController.class})
public class ScanExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ScanExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        String reason = e.getReason() != null ? e.getReason() : e.getStatusCode().toString();

        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", reason));
    }

    @ExceptionHandler(ScanStorageException.class)
    public ResponseEntity<Map<String, String>> storage(ScanStorageException e) {
        LOG.warn("Scan storage error", e);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }
}
