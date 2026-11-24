package com.ctrial.repo;

import com.ctrial.model.SafetyGlobalAggregate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SafetyGlobalAggregateRepo extends JpaRepository<SafetyGlobalAggregate, Long> {
    Optional<SafetyGlobalAggregate> findByCode(String code);
    List<SafetyGlobalAggregate> findAllByOrderByCreatedAtDesc();
}
