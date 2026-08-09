package com.powerbind.backend.service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.powerbind.backend.data.response.DashboardResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

// Handles all InfluxDB reads and writes for time-series sensor data
@Slf4j
@Service
@RequiredArgsConstructor
public class InfluxDBService {

    private final InfluxDBClient influxDBClient;

    @Value("${influxdb.bucket}")
    private String bucket;

    @Value("${influxdb.org}")
    private String orgName;

    // Write a presence detection event to InfluxDB
    public void writePresence(String roomName, boolean detected) {
        try {
            WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();
            Point point = Point.measurement("presence")
                    .addTag("room", roomName)
                    .addField("detected", detected ? 1 : 0)
                    .time(Instant.now(), WritePrecision.MS);
            writeApi.writePoint(point);
        } catch (Exception e) {
            log.error("[InfluxDB] Failed to write presence data: {}", e.getMessage());
        }
    }

    // Write power usage data from PZEM-004T to InfluxDB
    public void writePower(double watts, double voltage, double current, double kwh) {
        try {
            WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();
            Point point = Point.measurement("power")
                    .addField("watts", watts)
                    .addField("voltage", voltage)
                    .addField("current", current)
                    .addField("kwh", kwh)
                    .time(Instant.now(), WritePrecision.MS);
            writeApi.writePoint(point);
        } catch (Exception e) {
            log.error("[InfluxDB] Failed to write power data: {}", e.getMessage());
        }
    }

    // Query power history for the last N hours — used for dashboard charts
    public List<DashboardResponse.PowerHistory> queryPowerHistory(int hours) {
        String flux = String.format(
            "from(bucket: \"%s\") " +
            "|> range(start: -%dh) " +
            "|> filter(fn: (r) => r._measurement == \"power\") " +
            "|> filter(fn: (r) => r._field == \"watts\") " +
            "|> aggregateWindow(every: 15m, fn: mean, createEmpty: false) " +
            "|> yield(name: \"mean\")",
            bucket, hours
        );

        List<DashboardResponse.PowerHistory> result = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Jakarta"));

        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(flux, orgName);
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Object wattsVal = record.getValueByKey("_value");
                    if (wattsVal != null) {
                        result.add(DashboardResponse.PowerHistory.builder()
                                .timestamp(formatter.format(record.getTime()))
                                .watts(Double.parseDouble(wattsVal.toString()))
                                .kwh(0) // kWh aggregated separately
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[InfluxDB] Failed to query power history: {}", e.getMessage());
        }

        return result;
    }

    // Query total kWh consumed today
    public double queryTodayKwh() {
        String flux = String.format(
            "from(bucket: \"%s\") " +
            "|> range(start: today()) " +
            "|> filter(fn: (r) => r._measurement == \"power\" and r._field == \"kwh\") " +
            "|> last()",
            bucket
        );

        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(flux, orgName);
            if (!tables.isEmpty() && !tables.get(0).getRecords().isEmpty()) {
                Object val = tables.get(0).getRecords().get(0).getValue();
                if (val != null) return Double.parseDouble(val.toString());
            }
        } catch (Exception e) {
            log.error("[InfluxDB] Failed to query today kWh: {}", e.getMessage());
        }

        return 0.0;
    }

    // Query latest power reading in watts
    public double queryCurrentWatts() {
        String flux = String.format(
            "from(bucket: \"%s\") " +
            "|> range(start: -5m) " +
            "|> filter(fn: (r) => r._measurement == \"power\" and r._field == \"watts\") " +
            "|> last()",
            bucket
        );

        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(flux, orgName);
            if (!tables.isEmpty() && !tables.get(0).getRecords().isEmpty()) {
                Object val = tables.get(0).getRecords().get(0).getValue();
                if (val != null) return Double.parseDouble(val.toString());
            }
        } catch (Exception e) {
            log.error("[InfluxDB] Failed to query current watts: {}", e.getMessage());
        }

        return 0.0;
    }
}
