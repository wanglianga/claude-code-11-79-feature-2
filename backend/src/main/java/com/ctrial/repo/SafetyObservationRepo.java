package com.ctrial.repo;

import com.ctrial.model.SafetyObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SafetyObservationRepo extends JpaRepository<SafetyObservation, Long> {
    List<SafetyObservation> findAllByOrderByOccurredStartAscIdAsc();
    List<SafetyObservation> findByParticipantKeyOrderByOccurredStartAscRevisionAsc(String participantKey);
    List<SafetyObservation> findByObservationGroupKeyOrderByRevisionAsc(String observationGroupKey);
}
