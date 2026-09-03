package com.powerbind.backend.service;

import com.powerbind.backend.data.request.RoomRequest;
import com.powerbind.backend.data.response.RoomResponse;
import com.powerbind.backend.global.ResourceNotFoundException;
import com.powerbind.backend.model.Room;
import com.powerbind.backend.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final MqttPublisherService mqttPublisherService;

    // Get all rooms with live status
    public List<RoomResponse.Detail> getAllRooms() {
        return roomRepository.findAll().stream()
                .map(this::toDetail)
                .toList();
    }

    // Get a single room by id
    public RoomResponse.Detail getRoomById(UUID id) {
        Room room = findRoom(id);
        return toDetail(room);
    }

    // Create a new room
    @Transactional
    public RoomResponse.Detail createRoom(RoomRequest.Create request) {
        Room room = Room.builder()
                .name(request.getName())
                .mqttTopic(request.getMqttTopic())
                .build();
        room = roomRepository.save(room);
        return toDetail(room);
    }

    // Update room name
    @Transactional
    public RoomResponse.Detail updateRoom(UUID id, RoomRequest.Update request) {
        Room room = findRoom(id);
        room.setName(request.getName());
        roomRepository.save(room);
        return toDetail(room);
    }

    // Manual relay override — triggered from the dashboard, after the user
    // confirms via the validation dialog on the frontend. Updates DB state
    // immediately, then best-effort publishes the command to the ESP32 so the
    // device isn't left out of sync with what the dashboard shows.
    @Transactional
    public RoomResponse.Detail setRelay(UUID id, boolean relayOn) {
        Room room = findRoom(id);
        room.setRelayOn(relayOn);
        room.setNoPresenceSeconds(0);
        room.setUpdatedAt(LocalDateTime.now());
        room = roomRepository.save(room);

        mqttPublisherService.publishRelayCommand(room.getMqttTopic(), relayOn);

        return toDetail(room);
    }

    // Delete a room
    @Transactional
    public void deleteRoom(UUID id) {
        Room room = findRoom(id);
        roomRepository.delete(room);
    }

    // Internal helper used by MqttMessageHandler to update room presence state
    @Transactional
    public Room updatePresence(String mqttTopic, boolean detected) {
        Room room = roomRepository.findByMqttTopic(mqttTopic)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found for topic: " + mqttTopic));
        room.setPresenceDetected(detected);
        // Explicitly set updated_at manually to force the refresh
        room.setUpdatedAt(LocalDateTime.now());

        if (detected) {
            room.setNoPresenceSeconds(0);
            room.setRelayOn(true);
        } else {
            int counter = room.getNoPresenceSeconds() + 1;
            room.setNoPresenceSeconds(counter);

            // Auto-off after 120 seconds of no presence
            if (counter >= 120) {
                room.setRelayOn(false);
                room.setNoPresenceSeconds(0);
            }
        }

        return roomRepository.save(room);
    }

    private Room findRoom(UUID id) {
        return roomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
    }

    private RoomResponse.Detail toDetail(Room room) {
        return RoomResponse.Detail.builder()
                .id(room.getId().toString())
                .name(room.getName())
                .mqttTopic(room.getMqttTopic())
                .presenceDetected(room.isPresenceDetected())
                .relayOn(room.isRelayOn())
                .noPresenceSeconds(room.getNoPresenceSeconds())
                .createdAt(room.getCreatedAt())
                .build();
    }
}