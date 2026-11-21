package com.ocenering.api.db.repository;

import com.ocenering.api.db.entity.ScanModel;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanModelRepository extends JpaRepository<ScanModel, UUID> {

    List<ScanModel> findAllByTreeId(UUID treeId);
}
