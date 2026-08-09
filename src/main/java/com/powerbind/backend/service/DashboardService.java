package com.powerbind.backend.service;

import com.powerbind.backend.data.response.DashboardResponse;
import com.powerbind.backend.data.response.RoomResponse;
import com.powerbind.backend.model.Room;
import com.powerbind.backend.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final RoomRepository roomRepository;
    private final InfluxDBService influxDBService;

    // PLN tariff per kWh in rupiah (R1 900VA household tariff)
    private static final double PLN_TARIFF_PER_KWH = 1444.70;

    // Build the main dashboard summary
    public DashboardResponse.Summary getSummary() {
        List<Room> rooms = roomRepository.findAll();

        int occupiedRooms = (int) rooms.stream().filter(Room::isPresenceDetected).count();
        int activeDevices = (int) rooms.stream().filter(Room::isRelayOn).count();

        double currentWatts = influxDBService.queryCurrentWatts();
        double todayKwh = influxDBService.queryTodayKwh();
        double estimatedCost = todayKwh * PLN_TARIFF_PER_KWH;

        List<RoomResponse.Status> roomStatuses = rooms.stream()
                .map(r -> RoomResponse.Status.builder()
                        .id(r.getId().toString())
                        .name(r.getName())
                        .presenceDetected(r.isPresenceDetected())
                        .relayOn(r.isRelayOn())
                        .build())
                .toList();

        return DashboardResponse.Summary.builder()
                .totalRooms(rooms.size())
                .occupiedRooms(occupiedRooms)
                .activeDevices(activeDevices)
                .currentWatts(currentWatts)
                .todayKwh(todayKwh)
                .estimatedCostToday(estimatedCost)
                .rooms(roomStatuses)
                .build();
    }

    // Get power history for chart — last 24 hours by default
    public List<DashboardResponse.PowerHistory> getPowerHistory(int hours) {
        return influxDBService.queryPowerHistory(hours);
    }
}
