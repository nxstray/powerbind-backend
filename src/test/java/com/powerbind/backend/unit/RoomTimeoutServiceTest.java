package com.powerbind.backend.unit;

import com.powerbind.backend.data.response.RoomResponse;
import com.powerbind.backend.model.Room;
import com.powerbind.backend.repository.RoomRepository;
import com.powerbind.backend.service.RoomTimeoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Unit Test (room timeout)")
@ExtendWith(MockitoExtension.class)
class RoomTimeoutServiceTest {

    @Mock private RoomRepository roomRepository;
    @Mock private SimpMessagingTemplate websocket;

    @InjectMocks private RoomTimeoutService roomTimeoutService;

    // The scheduler marks a room offline once its last update is older than 30s,
    // so stay clear of the boundary in both directions to avoid timing flakiness.
    private static final int STALE_SECONDS = 31;
    private static final int FRESH_SECONDS = 5;

    private Room room;

    @BeforeEach
    void setUp() {
        room = Room.builder()
                .id(UUID.randomUUID())
                .name("Kamar 1")
                .mqttTopic("smart-home/room1")
                .presenceDetected(true)
                .relayOn(true)
                .noPresenceSeconds(42)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();
    }

    private void lastSeenSecondsAgo(int seconds) {
        room.setUpdatedAt(LocalDateTime.now().minusSeconds(seconds));
    }

    private Room freshRoom(String name, String topic) {
        return Room.builder()
                .id(UUID.randomUUID())
                .name(name)
                .mqttTopic(topic)
                .presenceDetected(true)
                .relayOn(true)
                .noPresenceSeconds(3)
                .updatedAt(LocalDateTime.now().minusSeconds(FRESH_SECONDS))
                .build();
    }

    @Test
    @DisplayName("TC-UNIT-TIMEOUT-01 a stale room is marked offline, saved, and pushed over WebSocket")
    void checkDeviceTimeouts_shouldMarkStaleRoomOffline() {
        lastSeenSecondsAgo(STALE_SECONDS);
        when(roomRepository.findAll()).thenReturn(List.of(room));

        roomTimeoutService.checkDeviceTimeouts();

        assertFalse(room.isPresenceDetected());
        assertFalse(room.isRelayOn());
        assertEquals(0, room.getNoPresenceSeconds());
        verify(roomRepository).save(room);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(websocket).convertAndSend(eq("/topic/presence"), payloadCaptor.capture());
        RoomResponse.Status status = (RoomResponse.Status) payloadCaptor.getValue();
        assertEquals(room.getId().toString(), status.getId());
        assertEquals("Kamar 1", status.getName());
        assertFalse(status.isPresenceDetected());
        assertFalse(status.isRelayOn());
    }

    @Test
    @DisplayName("TC-UNIT-TIMEOUT-02 a room seen within the 30s window is left untouched")
    void checkDeviceTimeouts_shouldKeepRecentlySeenRoom() {
        lastSeenSecondsAgo(FRESH_SECONDS);
        when(roomRepository.findAll()).thenReturn(List.of(room));

        roomTimeoutService.checkDeviceTimeouts();

        assertTrue(room.isPresenceDetected());
        assertTrue(room.isRelayOn());
        assertEquals(42, room.getNoPresenceSeconds());
        verify(roomRepository, never()).save(any());
        verifyNoInteractions(websocket);
    }

    @Test
    @DisplayName("TC-UNIT-TIMEOUT-03 a room without presence is never timed out")
    void checkDeviceTimeouts_shouldSkipRoomWithoutPresence() {
        room.setPresenceDetected(false);
        lastSeenSecondsAgo(STALE_SECONDS);
        when(roomRepository.findAll()).thenReturn(List.of(room));

        roomTimeoutService.checkDeviceTimeouts();

        verify(roomRepository, never()).save(any());
        verifyNoInteractions(websocket);
    }

    @Test
    @DisplayName("TC-UNIT-TIMEOUT-04 a room that was never updated is skipped safely (no NPE)")
    void checkDeviceTimeouts_shouldSkipRoomWithoutUpdatedAt() {
        room.setUpdatedAt(null);
        when(roomRepository.findAll()).thenReturn(List.of(room));

        assertDoesNotThrow(() -> roomTimeoutService.checkDeviceTimeouts());

        verify(roomRepository, never()).save(any());
        verifyNoInteractions(websocket);
    }

    @Test
    @DisplayName("TC-UNIT-TIMEOUT-05 only the stale room is handled when several rooms are checked")
    void checkDeviceTimeouts_shouldOnlyHandleStaleRooms() {
        lastSeenSecondsAgo(STALE_SECONDS);
        Room fresh = freshRoom("Ruang Tamu", "smart-home/room2");
        when(roomRepository.findAll()).thenReturn(List.of(room, fresh));

        roomTimeoutService.checkDeviceTimeouts();

        verify(roomRepository, times(1)).save(room);
        verify(roomRepository, never()).save(fresh);
        verify(websocket, times(1)).convertAndSend(eq("/topic/presence"), any(Object.class));

        assertFalse(room.isPresenceDetected());
        assertTrue(fresh.isPresenceDetected());
        assertTrue(fresh.isRelayOn());
        assertEquals(3, fresh.getNoPresenceSeconds());
    }
}
