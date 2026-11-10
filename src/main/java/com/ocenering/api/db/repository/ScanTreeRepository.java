package com.ocenering.api.db.repository;

import com.ocenering.api.db.entity.ScanTree;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanTreeRepository extends JpaRepository<ScanTree, UUID> {

    List<ScanTree> findAllByOrderByNameAsc();

    /** Matches the unique index on lower(name). */
    boolean existsByNameIgnoreCase(String name);
}

