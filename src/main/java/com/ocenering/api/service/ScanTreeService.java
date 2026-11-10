package com.ocenering.api.service;

import com.ocenering.api.auth.AuthenticatedUser;
import com.ocenering.api.db.entity.ScanTree;
import com.ocenering.api.db.repository.ScanTreeRepository;
import com.ocenering.api.iceberg.ScanStorageException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ScanTreeService {

    /** A scan tree as the client sees it; the stats come from Iceberg and are null when it is unreachable. */
    public record ScanTreeView(
            UUID id,
            String name,
            String description,
            OffsetDateTime createdAt,
            Integer scanCount,
            OffsetDateTime firstScanDate,
            OffsetDateTime lastScanDate) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(ScanTreeService.class);

    private final ScanTreeRepository trees;

    private final ScanService scans;

    public ScanTreeService(ScanTreeRepository trees, ScanService scans) {
        this.trees = trees;
        this.scans = scans;
    }

    public List<ScanTreeView> list() {
        Map<UUID, ScanService.TreeStats> stats = loadStats();

        return trees.findAllByOrderByNameAsc().stream()
                .map(t -> view(t, stats))
                .toList();
    }

    public Optional<ScanTreeView> get(UUID id) {
        return trees.findById(id).map(t -> view(t, loadStats()));
    }

    public boolean exists(UUID id) {
        return trees.existsById(id);
    }

    @Transactional
    public ScanTreeView create(String name, String description, AuthenticatedUser me) {
        String clean = name.strip();

        if (trees.existsByNameIgnoreCase(clean)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A scan tree called \"" + clean + "\" already exists");
        }

        ScanTree tree = trees.save(new ScanTree(clean, blankToNull(description), me != null ? me.id() : null));

        return view(tree, Map.of());
    }

    @Transactional
    public Optional<ScanTreeView> update(UUID id, String name, String description) {
        Optional<ScanTree> found = trees.findById(id);

        if (found.isEmpty()) {
            return Optional.empty();
        }

        ScanTree tree = found.get();

        if (name != null && !name.isBlank()) {
            String clean = name.strip();

            if (!clean.equalsIgnoreCase(tree.getName()) && trees.existsByNameIgnoreCase(clean)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A scan tree called \"" + clean + "\" already exists");
            }

            tree.setName(clean);
        }

        if (description != null) {
            tree.setDescription(blankToNull(description));
        }

        return Optional.of(view(trees.save(tree), loadStats()));
    }

    /** Deletes the tree's scans from Iceberg/S3 first, so a storage failure leaves the folder in place. */
    @Transactional
    public boolean delete(UUID id) {
        if (!trees.existsById(id)) {
            return false;
        }

        scans.deleteTree(id);
        trees.deleteById(id);

        return true;
    }

    private Map<UUID, ScanService.TreeStats> loadStats() {
        try {
            return scans.statsByTree();
        } catch (ScanStorageException e) {
            LOG.warn("Scan stats unavailable: {}", e.getMessage());

            return null;
        }
    }

    private static ScanTreeView view(ScanTree t, Map<UUID, ScanService.TreeStats> stats) {
        if (stats == null) {
            return new ScanTreeView(t.getId(), t.getName(), t.getDescription(), t.getCreatedAt(), null, null, null);
        }

        ScanService.TreeStats s = stats.get(t.getId());

        if (s == null) {
            return new ScanTreeView(t.getId(), t.getName(), t.getDescription(), t.getCreatedAt(), 0, null, null);
        }

        return new ScanTreeView(t.getId(), t.getName(), t.getDescription(), t.getCreatedAt(),
                s.scanCount(), s.firstScanDate(), s.lastScanDate());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

