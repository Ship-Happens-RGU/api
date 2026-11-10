package com.ocenering.api.controller.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocenering.api.auth.AuditService;
import com.ocenering.api.auth.AuthenticatedUser;
import com.ocenering.api.service.ScanService;
import com.ocenering.api.service.ScanTreeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/scan-trees")
public class ScanTreeController {

    public record CreateTreeRequest(@NotBlank @Size(max = 200) String name, @Size(max = 2000) String description) {
    }

    public record UpdateTreeRequest(@Size(max = 200) String name, @Size(max = 2000) String description) {
    }

    private final ScanTreeService trees;

    private final ScanService scans;

    private final AuditService audit;

    private final ObjectMapper json;

    public ScanTreeController(ScanTreeService trees, ScanService scans, AuditService audit, ObjectMapper json) {
        this.trees = trees;
        this.scans = scans;
        this.audit = audit;
        this.json = json;
    }

    @GetMapping
    public List<ScanTreeService.ScanTreeView> list() {
        return trees.list();
    }

    @PostMapping
    public ResponseEntity<ScanTreeService.ScanTreeView> create(@Valid @RequestBody CreateTreeRequest req,
                                                               @AuthenticationPrincipal AuthenticatedUser me,
                                                               HttpServletRequest http) {
        ScanTreeService.ScanTreeView tree = trees.create(req.name(), req.description(), me);
        audit.log("scan_tree_created", me.id(), me.email(), http);

        return ResponseEntity.status(HttpStatus.CREATED).body(tree);
    }

    @GetMapping("/{id}")
    public ScanTreeService.ScanTreeView get(@PathVariable UUID id) {
        return trees.get(id).orElseThrow(() -> notFound("Scan tree not found"));
    }

    @PatchMapping("/{id}")
    public ScanTreeService.ScanTreeView update(@PathVariable UUID id, @Valid @RequestBody UpdateTreeRequest req) {
        return trees.update(id, req.name(), req.description()).orElseThrow(() -> notFound("Scan tree not found"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser me,
                                       HttpServletRequest http) {
        if (!trees.delete(id)) {
            throw notFound("Scan tree not found");
        }

        audit.log("scan_tree_deleted", me.id(), me.email(), http);

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/scans")
    public List<ScanService.Scan> scans(@PathVariable UUID id) {
        requireTree(id);

        return scans.listByTree(id);
    }

    /** Multipart upload: the point-cloud file plus the date the scan was taken. */
    @PostMapping(path = "/{id}/scans", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@PathVariable UUID id,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam("scanDate") String scanDate,
                                    @RequestParam(value = "name", required = false) String name,
                                    @RequestParam(value = "notes", required = false) String notes,
                                    @AuthenticationPrincipal AuthenticatedUser me,
                                    HttpServletRequest http,
                                    HttpServletResponse response) throws IOException {
        requireTree(id);

        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The scan file is empty");
        }

        OffsetDateTime date = parseDate(scanDate);
        String displayName = name == null || name.isBlank() ? file.getOriginalFilename() : name.strip();

        if (displayName == null || displayName.isBlank()) {
            displayName = "Scan";
        }

        if (displayName.length() > 200 || (notes != null && notes.length() > 2000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name or notes are too long");
        }

        String accept = http.getHeader(HttpHeaders.ACCEPT);
        boolean streamEvents = accept != null && accept.contains("application/x-ndjson");

        if (streamEvents) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/x-ndjson;charset=UTF-8");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
            PrintWriter writer = response.getWriter();

            try {
                ScanService.UploadProgressListener listener = (phase, message, fraction, written, total) -> {
                    try {
                        Map<String, Object> event = new LinkedHashMap<>();
                        event.put("phase", phase);
                        event.put("message", message);
                        event.put("progress", fraction);
                        event.put("written", written);
                        event.put("total", total);
                        writer.println(json.writeValueAsString(event));
                        writer.flush();
                    } catch (IOException ignored) {
                        // client disconnected
                    }
                };

                ScanService.Scan scan = scans.upload(id, displayName, date, notes, file.getOriginalFilename(),
                        file.getSize(), file.getInputStream(), me, listener);
                audit.log("scan_uploaded", me.id(), me.email(), http);

                Map<String, Object> done = new LinkedHashMap<>();
                done.put("phase", "complete");
                done.put("message", "Upload complete!");
                done.put("progress", 1.0);
                done.put("scan", scan);
                writer.println(json.writeValueAsString(done));
                writer.flush();

                return null;
            } catch (Exception e) {
                Map<String, Object> err = Map.of("phase", "error", "message",
                        e.getMessage() != null ? e.getMessage() : "Upload failed");
                writer.println(json.writeValueAsString(err));
                writer.flush();

                return null;
            }
        }

        ScanService.Scan scan = scans.upload(id, displayName, date, notes, file.getOriginalFilename(),
                file.getSize(), file.getInputStream(), me);
        audit.log("scan_uploaded", me.id(), me.email(), http);

        return ResponseEntity.status(HttpStatus.CREATED).body(scan);
    }

    private void requireTree(UUID id) {
        if (!trees.exists(id)) {
            throw notFound("Scan tree not found");
        }
    }

    private static OffsetDateTime parseDate(String value) {
        try {
            return OffsetDateTime.parse(value.strip());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scanDate must be an ISO-8601 date-time with offset, e.g. 2026-11-10T09:30:00Z");
        }
    }

    static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}

