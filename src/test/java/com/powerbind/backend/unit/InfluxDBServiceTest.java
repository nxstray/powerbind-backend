package com.powerbind.backend.unit;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.powerbind.backend.data.response.DashboardResponse;
import com.powerbind.backend.service.InfluxDBService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Unit Test (influxdb)")
@ExtendWith(MockitoExtension.class)
class InfluxDBServiceTest {

    @Mock private InfluxDBClient influxDBClient;
    @Mock private QueryApi queryApi;
    @Mock private WriteApiBlocking writeApi;

    @InjectMocks private InfluxDBService influxDBService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(influxDBService, "bucket", "powerbind");
        ReflectionTestUtils.setField(influxDBService, "orgName", "powerbind-org");
    }

    // FluxRecord stores its values in a private map; the real getters read straight from it.
    private FluxRecord fluxRecord(Object value, Instant time) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        if (value != null) values.put("_value", value);
        if (time != null) values.put("_time", time);

        FluxRecord fluxRecord = new FluxRecord(0);
        ReflectionTestUtils.setField(fluxRecord, "values", values);
        return fluxRecord;
    }

    private FluxTable table(FluxRecord... records) {
        FluxTable fluxTable = new FluxTable();
        ReflectionTestUtils.setField(fluxTable, "records", List.of(records));
        return fluxTable;
    }

    private Point captureWrittenPoint() {
        ArgumentCaptor<Point> captor = ArgumentCaptor.forClass(Point.class);
        verify(writeApi).writePoint(captor.capture());
        return captor.getValue();
    }

    private String captureFlux(int hoursToQuery) {
        influxDBService.queryPowerHistory(hoursToQuery);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(queryApi).query(captor.capture(), eq("powerbind-org"));
        return captor.getValue();
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-01 writePresence writes a point with a sanitized room tag")
    void writePresence_shouldWriteSanitizedPoint() {
        when(influxDBClient.getWriteApiBlocking()).thenReturn(writeApi);

        influxDBService.writePresence("Ka\"mar\\1", true);

        String line = captureWrittenPoint().toLineProtocol();
        assertTrue(line.startsWith("presence,"), line);
        assertTrue(line.contains("room=Kamar1"), line);
        assertTrue(line.contains("detected="), line);
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-02 writePresence falls back to an unknown room tag for null names")
    void writePresence_shouldUseUnknownTag_whenRoomNameNull() {
        when(influxDBClient.getWriteApiBlocking()).thenReturn(writeApi);

        influxDBService.writePresence(null, false);

        assertTrue(captureWrittenPoint().toLineProtocol().contains("room=unknown"));
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-03 writePresence swallows write failures so MQTT handling keeps running")
    void writePresence_shouldSwallowWriteFailure() {
        when(influxDBClient.getWriteApiBlocking()).thenReturn(writeApi);
        doThrow(new RuntimeException("influx down")).when(writeApi).writePoint(any(Point.class));

        assertDoesNotThrow(() -> influxDBService.writePresence("Kamar 1", true));
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-04 writePower writes watts, voltage, current, and kwh fields")
    void writePower_shouldWriteAllFields() {
        when(influxDBClient.getWriteApiBlocking()).thenReturn(writeApi);

        influxDBService.writePower(120.5, 220.5, 2.5, 3.5);

        String line = captureWrittenPoint().toLineProtocol();
        assertTrue(line.startsWith("power "), line);
        assertTrue(line.contains("watts=120.5"), line);
        assertTrue(line.contains("voltage=220.5"), line);
        assertTrue(line.contains("current=2.5"), line);
        assertTrue(line.contains("kwh=3.5"), line);
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-05 writePower swallows write failures")
    void writePower_shouldSwallowWriteFailure() {
        when(influxDBClient.getWriteApiBlocking()).thenReturn(writeApi);
        doThrow(new RuntimeException("influx down")).when(writeApi).writePoint(any(Point.class));

        assertDoesNotThrow(() -> influxDBService.writePower(1.5, 220.5, 0.5, 0.5));
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-06 queryPowerHistory maps watts and formats timestamps in Jakarta time")
    void queryPowerHistory_shouldMapRecords() {
        FluxRecord first = fluxRecord(120.5, Instant.parse("2026-09-22T18:05:00Z"));
        FluxRecord second = fluxRecord(340.0, Instant.parse("2026-09-22T18:20:30Z"));
        when(influxDBClient.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString(), eq("powerbind-org"))).thenReturn(List.of(table(first, second)));

        List<DashboardResponse.PowerHistory> result = influxDBService.queryPowerHistory(6);

        assertEquals(2, result.size());
        assertEquals("01:05", result.get(0).getTimestamp());
        assertEquals(120.5, result.get(0).getWatts(), 0.0001);
        assertEquals(0.0, result.get(0).getKwh(), 0.0001);
        assertEquals("01:20", result.get(1).getTimestamp());
        assertEquals(340.0, result.get(1).getWatts(), 0.0001);
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-07 queryPowerHistory builds the mean-of-watts flux query for the bucket")
    void queryPowerHistory_shouldBuildFluxQuery() {
        when(influxDBClient.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString(), eq("powerbind-org"))).thenReturn(List.of());

        String flux = captureFlux(6);

        assertTrue(flux.contains("from(bucket: \"powerbind\")"), flux);
        assertTrue(flux.contains("range(start: -6h)"), flux);
        assertTrue(flux.contains("r._measurement == \"power\""), flux);
        assertTrue(flux.contains("aggregateWindow(every: 15m, fn: mean, createEmpty: false)"), flux);
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-08 queryPowerHistory clamps out-of-range hour windows to 24h")
    void queryPowerHistory_shouldSanitizeHours() {
        when(influxDBClient.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString(), eq("powerbind-org"))).thenReturn(List.of());

        for (int hours : new int[]{0, -5, 999}) {
            influxDBService.queryPowerHistory(hours);
        }

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(queryApi, times(3)).query(captor.capture(), eq("powerbind-org"));
        captor.getAllValues().forEach(flux -> assertTrue(flux.contains("range(start: -24h)"), flux));
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-09 queryPowerHistory returns an empty list when InfluxDB fails")
    void queryPowerHistory_shouldReturnEmpty_whenQueryFails() {
        when(influxDBClient.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString(), eq("powerbind-org"))).thenThrow(new RuntimeException("influx down"));

        assertTrue(influxDBService.queryPowerHistory(6).isEmpty());
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-10 queryPowerHistory skips records without a value")
    void queryPowerHistory_shouldSkipRecordsWithoutValue() {
        when(influxDBClient.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString(), eq("powerbind-org"))).thenReturn(List.of(
                table(fluxRecord(100.0, Instant.parse("2026-09-22T18:00:00Z")), fluxRecord(null, null))));

        List<DashboardResponse.PowerHistory> result = influxDBService.queryPowerHistory(6);

        assertEquals(1, result.size());
        assertEquals(100.0, result.get(0).getWatts(), 0.0001);
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-11 queryPowerHistory flattens records from multiple flux tables")
    void queryPowerHistory_shouldFlattenMultipleTables() {
        when(influxDBClient.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString(), eq("powerbind-org"))).thenReturn(List.of(
                table(fluxRecord(10.0, Instant.parse("2026-09-22T18:00:00Z"))),
                table(fluxRecord(20.0, Instant.parse("2026-09-22T18:15:00Z")))));

        List<DashboardResponse.PowerHistory> result = influxDBService.queryPowerHistory(1);

        assertEquals(2, result.size());
        assertEquals("01:00", result.get(0).getTimestamp());
        assertEquals("01:15", result.get(1).getTimestamp());
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-12 queryTodayKwh returns the last kWh reading of the day")
    void queryTodayKwh_shouldReturnLastValue() {
        when(influxDBClient.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString(), eq("powerbind-org")))
                .thenReturn(List.of(table(fluxRecord(4.75, Instant.parse("2026-09-22T18:00:00Z")))));

        assertEquals(4.75, influxDBService.queryTodayKwh(), 0.0001);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(queryApi).query(captor.capture(), eq("powerbind-org"));
        assertTrue(captor.getValue().contains("range(start: today())"), captor.getValue());
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-13 queryTodayKwh returns 0 for empty result sets and null values")
    void queryTodayKwh_shouldReturnZero_whenNoData() {
        when(influxDBClient.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString(), eq("powerbind-org")))
                .thenReturn(List.of())
                .thenReturn(List.of(table()))
                .thenReturn(List.of(table(fluxRecord(null, null))));

        assertEquals(0.0, influxDBService.queryTodayKwh(), 0.0001);
        assertEquals(0.0, influxDBService.queryTodayKwh(), 0.0001);
        assertEquals(0.0, influxDBService.queryTodayKwh(), 0.0001);
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-14 queryTodayKwh returns 0 when InfluxDB fails")
    void queryTodayKwh_shouldReturnZero_whenQueryFails() {
        when(influxDBClient.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString(), eq("powerbind-org"))).thenThrow(new RuntimeException("influx down"));

        assertEquals(0.0, influxDBService.queryTodayKwh(), 0.0001);
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-15 queryCurrentWatts returns the latest reading from the last 5 minutes")
    void queryCurrentWatts_shouldReturnLatestValue() {
        when(influxDBClient.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString(), eq("powerbind-org")))
                .thenReturn(List.of(table(fluxRecord(88.5, Instant.parse("2026-09-22T18:00:00Z")))));

        assertEquals(88.5, influxDBService.queryCurrentWatts(), 0.0001);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(queryApi).query(captor.capture(), eq("powerbind-org"));
        assertTrue(captor.getValue().contains("range(start: -5m)"), captor.getValue());
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-16 queryCurrentWatts returns 0 for empty result sets and null values")
    void queryCurrentWatts_shouldReturnZero_whenNoData() {
        when(influxDBClient.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString(), eq("powerbind-org")))
                .thenReturn(List.of())
                .thenReturn(List.of(table()))
                .thenReturn(List.of(table(fluxRecord(null, null))));

        assertEquals(0.0, influxDBService.queryCurrentWatts(), 0.0001);
        assertEquals(0.0, influxDBService.queryCurrentWatts(), 0.0001);
        assertEquals(0.0, influxDBService.queryCurrentWatts(), 0.0001);
    }

    @Test
    @DisplayName("TC-UNIT-INFLUX-17 queryCurrentWatts returns 0 when InfluxDB fails")
    void queryCurrentWatts_shouldReturnZero_whenQueryFails() {
        when(influxDBClient.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString(), eq("powerbind-org"))).thenThrow(new RuntimeException("influx down"));

        assertEquals(0.0, influxDBService.queryCurrentWatts(), 0.0001);
    }
}
