package com.ctrial.repo;

import com.ctrial.model.SafetyEpisodeRun;
import com.ctrial.model.SafetyEnums.EpisodeStrategy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SafetyEpisodeRunRepo extends JpaRepository<SafetyEpisodeRun, Long> {
    Optional<SafetyEpisodeRun> findByCode(String code);
    List<SafetyEpisodeRun> findAllByOrderByCreatedAtDesc();
    List<SafetyEpisodeRun> findByStrategyOrderByCreatedAtDesc(EpisodeStrategy strategy);
}
