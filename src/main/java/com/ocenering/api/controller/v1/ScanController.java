package com.ocenering.api.controller.v1;

import com.ocenering.api.auth.AuditService;
import com.ocenering.api.auth.AuthenticatedUser;
import com.ocenering.api.service.ScanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.SeekableInputStream;
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

    /** Streams the point-cloud file straight from S3, supporting HTTP Range requests for progressive viewer streaming. */
    @GetMapping("/{scanId}/file")
    public void download(@PathVariable UUID scanId, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ScanService.Scan scan = scans.find(scanId).orElseThrow(() -> ScanTreeController.notFound("Scan not found"));
        InputFile file = scans.openFile(scanId).orElseThrow(() -> ScanTreeController.notFound("Scan not found"));
        long totalLength = file.getLength();

        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(scan.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable");

        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        long start = 0;
        long end = totalLength - 1;
        boolean isPartial = false;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=") && totalLength > 0) {
            String spec = rangeHeader.substring("bytes=".length()).trim();
            int comma = spec.indexOf(',');

            if (comma >= 0) {
                spec = spec.substring(0, comma).trim();
            }

            int dash = spec.indexOf('-');

            if (dash >= 0) {
                String startPart = spec.substring(0, dash).trim();
                String endPart = spec.substring(dash + 1).trim();

                try {
                    if (startPart.isEmpty()) {
                        long suffix = Long.parseLong(endPart);
                        start = Math.max(0, totalLength - suffix);
                        end = totalLength - 1;
                    } else {
                        start = Long.parseLong(startPart);
                        end = endPart.isEmpty() ? totalLength - 1 : Math.min(totalLength - 1, Long.parseLong(endPart));
                    }

                    if (start >= 0 && start <= end && start < totalLength) {
                        isPartial = true;
                    }
                } catch (NumberFormatException ignored) {
                    isPartial = false;
                }
            }
        }

        if (isPartial) {
            long contentLength = end - start + 1;
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + totalLength);
            response.setContentLengthLong(contentLength);

            try (SeekableInputStream in = file.newStream(); OutputStream out = response.getOutputStream()) {
                in.seek(start);
                byte[] buffer = new byte[65536];
                long remaining = contentLength;

                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    int read = in.read(buffer, 0, toRead);

                    if (read < 0) {
                        break;
                    }

                    out.write(buffer, 0, read);
                    remaining -= read;
                }
            }

            return;
        }

        response.setContentLengthLong(totalLength);

        try (SeekableInputStream in = file.newStream(); OutputStream out = response.getOutputStream()) {
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

