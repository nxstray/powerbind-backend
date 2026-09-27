package com.powerbind.backend.repository;

import com.powerbind.backend.model.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    Optional<Room> findByMqttTopic(String mqttTopic);
}
