package com.ocenering.api.controller.v1;

import com.ocenering.api.auth.AuditService;
import com.ocenering.api.auth.AuthenticatedUser;
import com.ocenering.api.service.ScanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.iceberg.io.InputFile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scans")
public class ScanController {

    private final ScanService scans;

    private final AuditService audit;

    public ScanController(ScanService scans, AuditService audit) {
        this.scans = scans;
        this.audit = audit;
    }

    @GetMapping("/{scanId}")
    public ScanService.Scan get(@PathVariable UUID scanId) {
        return scans.find(scanId).orElseThrow(() -> ScanTreeController.notFound("Scan not found"));
    }

    /** Streams the point-cloud file straight from S3 (synchronously, so the session filter still applies). */
    @GetMapping("/{scanId}/file")
    public void download(@PathVariable UUID scanId, HttpServletResponse response) throws IOException {
        ScanService.Scan scan = scans.find(scanId).orElseThrow(() -> ScanTreeController.notFound("Scan not found"));
        InputFile file = scans.openFile(scanId).orElseThrow(() -> ScanTreeController.notFound("Scan not found"));

        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLengthLong(file.getLength());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(scan.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable");

        try (InputStream in = file.newStream(); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    @DeleteMapping("/{scanId}")
    public ResponseEntity<Void> delete(@PathVariable UUID scanId, @AuthenticationPrincipal AuthenticatedUser me,
                                       HttpServletRequest http) {
        if (!scans.delete(scanId)) {
            throw ScanTreeController.notFound("Scan not found");
        }

        audit.log("scan_deleted", me.id(), me.email(), http);

        return ResponseEntity.noContent().build();
    }
}

