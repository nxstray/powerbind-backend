package com.powerbind.backend.unit;

import com.powerbind.backend.data.request.RoomRequest;
import com.powerbind.backend.data.response.AnomalyEventResponse;
import com.powerbind.backend.data.response.RoomResponse;
import com.powerbind.backend.global.ResourceNotFoundException;
import com.powerbind.backend.model.Room;
import com.powerbind.backend.repository.RoomRepository;
import com.powerbind.backend.service.MqttPublisherService;
import com.powerbind.backend.service.RoomService;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Unit Test (room)")
@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock private RoomRepository roomRepository;
    @Mock private MqttPublisherService mqttPublisherService;
    @Mock private SimpMessagingTemplate messagingTemplate;

    @InjectMocks private RoomService roomService;

    private Room room;

    @BeforeEach
    void setUp() {
        room = Room.builder()
                .id(UUID.randomUUID())
                .name("Kamar 1")
                .mqttTopic("smart-home/room1")
                .presenceDetected(false)
                .relayOn(false)
                .noPresenceSeconds(0)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private RoomRequest.Create createRequest(String name, String topic) {
        RoomRequest.Create req = new RoomRequest.Create();
        req.setName(name);
        req.setMqttTopic(topic);
        return req;
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-01 getAllRooms maps every stored room to its detail")
    void getAllRooms_shouldMapAllRooms() {
        when(roomRepository.findAll()).thenReturn(List.of(room));

        List<RoomResponse.Detail> result = roomService.getAllRooms();

        assertEquals(1, result.size());
        assertEquals("Kamar 1", result.get(0).getName());
        assertEquals(room.getId().toString(), result.get(0).getId());
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-02 getRoomById returns the mapped detail")
    void getRoomById_shouldReturnDetail() {
        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));

        RoomResponse.Detail detail = roomService.getRoomById(room.getId());

        assertEquals("smart-home/room1", detail.getMqttTopic());
        assertFalse(detail.isRelayOn());
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-03 getRoomById with unknown id throws ResourceNotFoundException")
    void getRoomById_shouldThrow_whenRoomNotFound() {
        when(roomRepository.findById(room.getId())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> roomService.getRoomById(room.getId()));
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-04 createRoom persists the room with name and topic")
    void createRoom_shouldSaveAndReturnDetail() {
        // Mimic JPA: the @GeneratedValue UUID is populated during save
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> {
            Room saved = inv.getArgument(0);
            saved.setId(room.getId());
            return saved;
        });

        RoomResponse.Detail detail = roomService.createRoom(createRequest("Ruang Tamu", "smart-home/tamu"));

        assertEquals("Ruang Tamu", detail.getName());
        assertEquals("smart-home/tamu", detail.getMqttTopic());
        assertEquals(room.getId().toString(), detail.getId());
        verify(roomRepository).save(any(Room.class));
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-05 updateRoom renames the room and persists it")
    void updateRoom_shouldRenameAndSave() {
        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        RoomRequest.Update req = new RoomRequest.Update();
        req.setName("Nama Baru");

        RoomResponse.Detail detail = roomService.updateRoom(room.getId(), req);

        assertEquals("Nama Baru", detail.getName());
        verify(roomRepository).save(room);
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-06 deleteRoom removes the room found by id")
    void deleteRoom_shouldDeleteExistingRoom() {
        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));

        roomService.deleteRoom(room.getId());

        verify(roomRepository).delete(room);
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-07 setRelay(true) publishes the MQTT command and resets the no-presence counter")
    void setRelay_shouldPublishCommandAndResetCounter() {
        room.setPresenceDetected(true);
        room.setNoPresenceSeconds(30);
        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        RoomResponse.Detail detail = roomService.setRelay(room.getId(), true);

        assertTrue(detail.isRelayOn());
        assertEquals(0, detail.getNoPresenceSeconds());
        verify(mqttPublisherService).publishRelayCommand("smart-home/room1", true);
        // Room occupied (presence on) — turning the relay on is NOT a waste
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-08 setRelay(true) on an EMPTY room publishes the anomaly exactly once")
    void setRelay_shouldPublishAnomaly_whenNewlyWasting() {
        // Relay off + no presence before, relay ON + no presence after → transition
        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        roomService.setRelay(room.getId(), true);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/anomaly"), payloadCaptor.capture());
        AnomalyEventResponse event = (AnomalyEventResponse) payloadCaptor.getValue();
        assertEquals(room.getId().toString(), event.getRoomId());
        assertEquals("Kamar 1", event.getRoomName());
        assertTrue(event.getMessage().contains("Kamar 1"));
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-09 setRelay does NOT re-publish the anomaly while the waste continues")
    void setRelay_shouldNotRePublish_whenAlreadyWasting() {
        room.setRelayOn(true); // already wasting (relay on, no presence)
        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        roomService.setRelay(room.getId(), true);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-10 setRelay(false) never publishes an anomaly")
    void setRelay_shouldNotPublishAnomaly_whenTurningOff() {
        room.setRelayOn(true);
        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        roomService.setRelay(room.getId(), false);

        verify(mqttPublisherService).publishRelayCommand("smart-home/room1", false);
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-11 updatePresence(detected=true) turns the relay ON and resets the counter")
    void updatePresence_shouldTurnRelayOn_whenPresenceDetected() {
        room.setRelayOn(false);
        room.setNoPresenceSeconds(50);
        when(roomRepository.findByMqttTopic("smart-home/room1")).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        Room updated = roomService.updatePresence("smart-home/room1", true);

        assertTrue(updated.isPresenceDetected());
        assertTrue(updated.isRelayOn());
        assertEquals(0, updated.getNoPresenceSeconds());
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-12 updatePresence(detected=false) increments the no-presence counter")
    void updatePresence_shouldIncrementCounter_whenNoPresence() {
        room.setPresenceDetected(true);
        room.setRelayOn(true);
        when(roomRepository.findByMqttTopic("smart-home/room1")).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        Room updated = roomService.updatePresence("smart-home/room1", false);

        assertFalse(updated.isPresenceDetected());
        assertTrue(updated.isRelayOn());
        assertEquals(1, updated.getNoPresenceSeconds());
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-13 updatePresence auto-offs the relay after 120s without presence")
    void updatePresence_shouldAutoOff_after120Seconds() {
        room.setPresenceDetected(false);
        room.setRelayOn(true);
        room.setNoPresenceSeconds(119);
        when(roomRepository.findByMqttTopic("smart-home/room1")).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        Room updated = roomService.updatePresence("smart-home/room1", false);

        assertFalse(updated.isRelayOn());
        assertEquals(0, updated.getNoPresenceSeconds());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-14 updatePresence with an unknown MQTT topic throws")
    void updatePresence_shouldThrow_whenTopicUnknown() {
        when(roomRepository.findByMqttTopic("smart-home/ghost")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> roomService.updatePresence("smart-home/ghost", true));
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-15 updatePresence publishes the anomaly on the transition into wasting")
    void updatePresence_shouldPublishAnomaly_whenNewlyWasting() {
        room.setPresenceDetected(true);
        room.setRelayOn(true);
        when(roomRepository.findByMqttTopic("smart-home/room1")).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        roomService.updatePresence("smart-home/room1", false);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/anomaly"), payloadCaptor.capture());
        AnomalyEventResponse event = (AnomalyEventResponse) payloadCaptor.getValue();
        assertEquals(room.getId().toString(), event.getRoomId());
    }

    @Test
    @DisplayName("TC-UNIT-ROOM-16 updatePresence does NOT re-publish while the waste continues")
    void updatePresence_shouldNotRePublish_whenAlreadyWasting() {
        room.setPresenceDetected(false);
        room.setRelayOn(true);
        room.setNoPresenceSeconds(10); // already wasting
        when(roomRepository.findByMqttTopic("smart-home/room1")).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        roomService.updatePresence("smart-home/room1", false);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }
}
