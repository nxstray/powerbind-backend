package com.powerbind.backend.data.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

public class DashboardResponse {

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Summary {
        // Total rooms registered in the system
        private int totalRooms;
        // Rooms currently occupied
        private int occupiedRooms;
        // Rooms with relay currently on
        private int activeDevices;
        // Current total power usage in watts (from PZEM)
        private double currentWatts;
        // Total energy consumed today in kWh
        private double todayKwh;
        // Estimated cost today based on PLN tariff
        private double estimatedCostToday;
        // Per-room live status
        private List<RoomResponse.Status> rooms;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class PowerHistory {
        // Timestamp label for the chart x-axis
        private String timestamp;
        // Watt reading at this timestamp
        private double watts;
        // Cumulative kWh at this timestamp
        private double kwh;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class PresenceHistory {
        private String roomName;
        // Map of hour -> detection count for the day chart
        private Map<String, Long> hourlyDetections;
    }
}
