package com.ctrial.repo;

import com.ctrial.model.TerminologyVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TerminologyVersionRepo extends JpaRepository<TerminologyVersion, Long> {
    Optional<TerminologyVersion> findByCodingSystemAndVersion(String codingSystem, String version);
    List<TerminologyVersion> findAllByOrderByCodingSystemAscVersionAsc();
    List<TerminologyVersion> findByCodingSystemOrderByVersionAsc(String codingSystem);
}
