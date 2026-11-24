package com.ctrial.repo;

import com.ctrial.model.SafetyStatsCell;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SafetyStatsCellRepo extends JpaRepository<SafetyStatsCell, Long> {
    List<SafetyStatsCell> findByVersionIdOrderByPreferredTermAsc(Long versionId);
}
