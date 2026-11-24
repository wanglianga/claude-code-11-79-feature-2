package com.ctrial.repo;

import com.ctrial.model.ExposureWindow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExposureWindowRepo extends JpaRepository<ExposureWindow, Long> {
    List<ExposureWindow> findAllByOrderByParticipantKeyAscStartDayAsc();
    List<ExposureWindow> findByParticipantKeyOrderByStartDayAsc(String participantKey);
}
