package com.ctrial.repo;

import com.ctrial.model.SafetyStatsVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SafetyStatsVersionRepo extends JpaRepository<SafetyStatsVersion, Long> {
    Optional<SafetyStatsVersion> findByCode(String code);
    List<SafetyStatsVersion> findAllByOrderByCreatedAtDesc();
    List<SafetyStatsVersion> findAllByOrderByCodeAsc();
}
