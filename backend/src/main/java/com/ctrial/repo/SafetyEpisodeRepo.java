package com.ctrial.repo;

import com.ctrial.model.SafetyEpisode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SafetyEpisodeRepo extends JpaRepository<SafetyEpisode, Long> {
    List<SafetyEpisode> findByRunIdOrderByParticipantKeyAscOccurrenceIndexAsc(Long runId);
    List<SafetyEpisode> findByRunIdAndParticipantKeyOrderByOccurrenceIndexAsc(Long runId, String participantKey);
    long countByRunId(Long runId);
}
