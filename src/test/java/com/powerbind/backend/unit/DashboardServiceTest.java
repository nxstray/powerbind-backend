package com.powerbind.backend.unit;

import com.powerbind.backend.data.response.DashboardResponse;
import com.powerbind.backend.model.Room;
import com.powerbind.backend.repository.RoomRepository;
import com.powerbind.backend.service.DashboardService;
import com.powerbind.backend.service.InfluxDBService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Unit Test (dashboard)")
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private RoomRepository roomRepository;
    @Mock private InfluxDBService influxDBService;

    @InjectMocks private DashboardService dashboardService;

    private Room occupiedOn;
    private Room emptyOff;

    @BeforeEach
    void setUp() {
        occupiedOn = Room.builder()
                .id(UUID.randomUUID()).name("Kamar 1").mqttTopic("t1")
                .presenceDetected(true).relayOn(true).build();
        emptyOff = Room.builder()
                .id(UUID.randomUUID()).name("Kamar 2").mqttTopic("t2")
                .presenceDetected(false).relayOn(false).build();
    }

    @Test
    @DisplayName("TC-UNIT-DASH-01 getSummary counts rooms, occupancy, and active devices")
    void getSummary_shouldAggregateRoomStats() {
        when(roomRepository.findAll()).thenReturn(List.of(occupiedOn, emptyOff));
        when(influxDBService.queryCurrentWatts()).thenReturn(120.5);
        when(influxDBService.queryTodayKwh()).thenReturn(2.0);

        DashboardResponse.Summary s = dashboardService.getSummary();

        assertEquals(2, s.getTotalRooms());
        assertEquals(1, s.getOccupiedRooms());
        assertEquals(1, s.getActiveDevices());
        assertEquals(120.5, s.getCurrentWatts());
        assertEquals(2.0, s.getTodayKwh());
    }

    @Test
    @DisplayName("TC-UNIT-DASH-02 estimated cost = today kWh x PLN tariff (Rp 1.444,70/kWh)")
    void getSummary_shouldComputeEstimatedCostFromTariff() {
        when(roomRepository.findAll()).thenReturn(List.of());
        when(influxDBService.queryCurrentWatts()).thenReturn(0.0);
        when(influxDBService.queryTodayKwh()).thenReturn(3.0);

        DashboardResponse.Summary s = dashboardService.getSummary();

        assertEquals(3.0 * 1444.70, s.getEstimatedCostToday(), 0.001);
    }

    @Test
    @DisplayName("TC-UNIT-DASH-03 room statuses are mapped with id, name, presence, and relay")
    void getSummary_shouldMapRoomStatuses() {
        when(roomRepository.findAll()).thenReturn(List.of(occupiedOn, emptyOff));
        when(influxDBService.queryCurrentWatts()).thenReturn(0.0);
        when(influxDBService.queryTodayKwh()).thenReturn(0.0);

        DashboardResponse.Summary s = dashboardService.getSummary();

        assertEquals(2, s.getRooms().size());
        assertEquals(occupiedOn.getId().toString(), s.getRooms().get(0).getId());
        assertEquals("Kamar 1", s.getRooms().get(0).getName());
        assertTrue(s.getRooms().get(0).isPresenceDetected());
        assertTrue(s.getRooms().get(0).isRelayOn());
        assertFalse(s.getRooms().get(1).isPresenceDetected());
        assertFalse(s.getRooms().get(1).isRelayOn());
    }

    @Test
    @DisplayName("TC-UNIT-DASH-04 getPowerHistory delegates the hour window to InfluxDBService")
    void getPowerHistory_shouldDelegateToInflux() {
        when(influxDBService.queryPowerHistory(6)).thenReturn(List.of());

        List<DashboardResponse.PowerHistory> result = dashboardService.getPowerHistory(6);

        assertTrue(result.isEmpty());
        verify(influxDBService).queryPowerHistory(6);
    }
}
