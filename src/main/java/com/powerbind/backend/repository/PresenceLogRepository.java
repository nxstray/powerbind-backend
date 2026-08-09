package com.powerbind.backend.repository;

import com.powerbind.backend.model.PresenceLog;
import com.powerbind.backend.model.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PresenceLogRepository extends JpaRepository<PresenceLog, UUID> {

    // Fetch all logs for a room within a time range for history display
    List<PresenceLog> findByRoomAndCreatedAtBetweenOrderByCreatedAtDesc(
            Room room, LocalDateTime from, LocalDateTime to);

    // Count detections per room for dashboard stats
    long countByRoomAndDetectedTrue(Room room);
}
