package com.ctrial.repo;

import com.ctrial.model.EpisodeEdge;
import com.ctrial.model.SafetyEnums.EdgeKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EpisodeEdgeRepo extends JpaRepository<EpisodeEdge, Long> {
    List<EpisodeEdge> findByRunIdOrderByScoreDesc(Long runId);
    List<EpisodeEdge> findByRunIdAndKind(Long runId, EdgeKind kind);
    long countByRunIdAndKind(Long runId, EdgeKind kind);
}
