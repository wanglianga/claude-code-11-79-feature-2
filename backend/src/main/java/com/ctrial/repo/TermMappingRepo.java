package com.ctrial.repo;

import com.ctrial.model.TermMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TermMappingRepo extends JpaRepository<TermMapping, Long> {
    List<TermMapping> findByTerminologyVersionIdOrderBySourceTermAsc(Long terminologyVersionId);
    List<TermMapping> findByTerminologyVersionCodingSystemAndTerminologyVersionVersionOrderBySourceTermAsc(
            String codingSystem, String version);
}
