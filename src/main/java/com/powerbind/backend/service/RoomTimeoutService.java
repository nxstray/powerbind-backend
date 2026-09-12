package com.powerbind.backend.service;

import com.powerbind.backend.data.response.RoomResponse;
import com.powerbind.backend.model.Room;
import com.powerbind.backend.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomTimeoutService {

    private final RoomRepository roomRepository;
    private final SimpMessagingTemplate websocket;

    @Scheduled(fixedRate = 2000) // Checking every 2 seconds is sufficient
    @Transactional
    public void checkDeviceTimeouts() {
        List<Room> rooms = roomRepository.findAll();
        LocalDateTime now = LocalDateTime.now();

        for (Room room : rooms) {
            // Only check for rooms that are marked as ON
            if (room.isPresenceDetected() && room.getUpdatedAt() != null) {
                
                // Increase threshold to 15 seconds to allow for network jitter
                if (room.getUpdatedAt().plusSeconds(30).isBefore(now)) {
                    
                    room.setPresenceDetected(false);
                    room.setRelayOn(false);
                    room.setNoPresenceSeconds(0);
                    roomRepository.save(room);

                    RoomResponse.Status status = RoomResponse.Status.builder()
                            .id(room.getId().toString())
                            .name(room.getName())
                            .presenceDetected(false)
                            .relayOn(false)
                            .build();

                    websocket.convertAndSend("/topic/presence", status);
                    log.info("[Timeout] Room {} marked offline", room.getName());
                }
            }
        }
    }
}