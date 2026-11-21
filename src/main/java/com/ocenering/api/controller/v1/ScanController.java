package com.ocenering.api.controller.v1;

import com.ocenering.api.auth.AuditService;
import com.ocenering.api.auth.AuthenticatedUser;
import com.ocenering.api.iceberg.ScanStorageException;
import com.ocenering.api.service.ScanModelService;
import com.ocenering.api.service.ScanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.RangeReadable;
import org.apache.iceberg.io.SeekableInputStream;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/scans")
public class ScanController {

    private final ScanService scans;

    private final ScanModelService models;

    private final AuditService audit;

    public ScanController(ScanService scans, ScanModelService models, AuditService audit) {
        this.scans = scans;
        this.models = models;
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
        ScanService.DownloadableScan dl = scans.openDownload(scanId)
                .orElseThrow(() -> ScanTreeController.notFound("Scan not found"));
        ScanService.Scan scan = dl.scan();
        InputFile file = dl.file();
        long totalLength = scan.fileSize() > 0 ? scan.fileSize() : file.getLength();

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
                if (in instanceof RangeReadable rr) {
                    int chunkSize = (int) Math.min(contentLength, 8L * 1024 * 1024);
                    byte[] buffer = new byte[chunkSize];
                    long pos = start;
                    long remaining = contentLength;

                    while (remaining > 0) {
                        int toRead = (int) Math.min(buffer.length, remaining);
                        rr.readFully(pos, buffer, 0, toRead);
                        out.write(buffer, 0, toRead);
                        pos += toRead;
                        remaining -= toRead;
                    }
                } else {
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
            }

            return;
        }

        response.setContentLengthLong(totalLength);

        try (SeekableInputStream in = file.newStream(); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    /** Stores the GLB mesh the browser converted from this scan's point cloud (replacing any earlier one). */
    @PostMapping(path = "/{scanId}/model", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ScanModelService.ModelInfo> uploadModel(@PathVariable UUID scanId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "vertexCount", required = false) Long vertexCount,
            @RequestParam(value = "triangleCount", required = false) Long triangleCount,
            @RequestParam(value = "voxelCm", required = false) Double voxelCm,
            @AuthenticationPrincipal AuthenticatedUser me,
            HttpServletRequest http) throws IOException {
        ScanService.Scan scan = scans.find(scanId).orElseThrow(() -> ScanTreeController.notFound("Scan not found"));

        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The model file is empty");
        }

        ScanModelService.ModelInfo info;

        try {
            info = models.store(scanId, scan.treeId(), file.getInputStream(),
                    new ScanModelService.ModelStats(vertexCount, triangleCount, voxelCm), me.email());
        } catch (ScanStorageException e) {
            if (e.getCause() instanceof IllegalArgumentException bad) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, bad.getMessage());
            }

            throw e;
        }

        audit.log("scan_model_uploaded", me.id(), me.email(), http);

        return ResponseEntity.status(HttpStatus.CREATED).body(info);
    }

    /** Streams the scan's GLB model. 404 when the scan has not been converted yet. */
    @GetMapping("/{scanId}/model")
    public void downloadModel(@PathVariable UUID scanId, HttpServletResponse response) throws IOException {
        Map.Entry<ScanModelService.ModelInfo, InputFile> model = models.open(scanId)
                .orElseThrow(() -> ScanTreeController.notFound("This scan has no model yet"));

        response.setContentType("model/gltf-binary");
        response.setContentLengthLong(model.getKey().fileSize());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(scanId + ".glb", StandardCharsets.UTF_8)
                .build()
                .toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-cache");

        try (SeekableInputStream in = model.getValue().newStream(); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    @DeleteMapping("/{scanId}/model")
    public ResponseEntity<Void> deleteModel(@PathVariable UUID scanId) {
        if (!models.delete(scanId)) {
            throw ScanTreeController.notFound("This scan has no model");
        }

        return ResponseEntity.noContent().build();
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

