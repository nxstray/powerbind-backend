package com.powerbind.backend.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerbind.backend.service.PrometheusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Unit Test (prometheus proxy)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrometheusServiceTest {

    private PrometheusService service;
    private WebClient webClient;
    private WebClient.RequestHeadersUriSpec<?> uriSpec;
    private WebClient.RequestHeadersSpec<?> headersSpec;
    private WebClient.ResponseSpec responseSpec;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        service = new PrometheusService("http://test");
        webClient = mock(WebClient.class);
        uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        headersSpec = mock(WebClient.RequestHeadersSpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);
        when(webClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) uriSpec);
        doReturn((WebClient.RequestHeadersSpec) headersSpec)
                .when(uriSpec).uri(ArgumentMatchers.<java.util.function.Function<UriBuilder, URI>>any());
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        ReflectionTestUtils.setField(service, "webClient", webClient);
    }

    private void stubResponse(JsonNode body) {
        when(responseSpec.bodyToMono(JsonNode.class)).thenReturn(Mono.just(body));
    }

    private static JsonNode json(String raw) {
        try {
            return new ObjectMapper().readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Unchecked casts live in these two helpers only, so the assertions below
     * stay free of type-safety warnings.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> seriesOf(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("series");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pointsOf(Map<String, Object> series) {
        return (List<Map<String, Object>>) series.get("points");
    }

    // ---- deriveSeriesName (tested through queryRange - package-private helper) ----

    @Test
    @DisplayName("TC-UNIT-PROM-01 with groupBy: label value names the series; missing label falls back to the metric")
    void queryRange_shouldNameSeriesByLabel_whenGrouping() {
        stubResponse(json("{\"data\":{\"result\":["
                + "{\"metric\":{\"area\":\"heap\"},\"values\":[[1690000000,\"1\"]]},"
                + "{\"metric\":{},\"values\":[[1690000000,\"2\"]]}"
                + "]}}"));

        var result = service.queryRange("m", "area", "sum", 1, 60);

        List<Map<String, Object>> series = seriesOf(result);
        // peak 2 (no label) sorts first and falls back to the metric name
        assertEquals("m", series.get(0).get("name"));
        assertEquals("heap", series.get(1).get("name"));
    }

    @Test
    @DisplayName("TC-UNIT-PROM-03 without groupBy: legend formatted like Grafana Explore (__name__ first)")
    void queryRange_shouldFormatFullLabelSet_whenNoGrouping() {
        stubResponse(json("{\"data\":{\"result\":["
                + "{\"metric\":{\"application\":\"powerbind-backend\",\"area\":\"heap\"},\"values\":[[1690000000,\"7\"]]}"
                + "]}}"));

        var result = service.queryRange("jvm_memory_used_bytes", null, "sum", 1, 60);

        List<Map<String, Object>> series = seriesOf(result);
        String name = (String) series.get(0).get("name");
        assertTrue(name.startsWith("{__name__=\"jvm_memory_used_bytes\""));
        assertTrue(name.contains(", application=\"powerbind-backend\""));
        assertTrue(name.contains(", area=\"heap\""));
        assertTrue(name.endsWith("}"));
    }

    // ---- getMetricNames (dropdown) ----

    @Test
    @DisplayName("TC-UNIT-PROM-04 metric names are parsed and sorted alphabetically")
    void getMetricNames_shouldParseAndSort() {
        stubResponse(json("{\"status\":\"success\",\"data\":[\"b_metric\",\"a_metric\",\"c_metric\"]}"));

        List<String> names = service.getMetricNames();

        assertEquals(List.of("a_metric", "b_metric", "c_metric"), names);
    }

    @Test
    @DisplayName("TC-UNIT-PROM-05 names are cached for 5 minutes (second call does not re-fetch)")
    void getMetricNames_shouldUseCache_withinTtl() {
        stubResponse(json("{\"data\":[\"m1\"]}"));

        service.getMetricNames();
        service.getMetricNames();

        verify(webClient, times(1)).get();
    }

    @Test
    @DisplayName("TC-UNIT-PROM-06 a failed fetch degrades to an empty list (no exception)")
    void getMetricNames_shouldReturnEmpty_whenFetchFails() {
        when(responseSpec.bodyToMono(JsonNode.class)).thenReturn(Mono.empty());

        assertTrue(service.getMetricNames().isEmpty());
    }

    // ---- queryRange: input sanitization (no free text reaches PromQL) ----

    @Test
    @DisplayName("TC-UNIT-PROM-07 invalid metric name is rejected (injection guard)")
    void queryRange_shouldReject_invalidMetricName() {
        assertThrows(IllegalArgumentException.class,
                () -> service.queryRange("bad metric; DROP", null, "sum", 1, 60));
        verify(webClient, never()).get();
    }

    @Test
    @DisplayName("TC-UNIT-PROM-08 aggregation outside the whitelist is rejected")
    void queryRange_shouldReject_unknownAgg() {
        assertThrows(IllegalArgumentException.class,
                () -> service.queryRange("jvm_memory_used_bytes", null, "topk", 1, 60));
        verify(webClient, never()).get();
    }

    @Test
    @DisplayName("TC-UNIT-PROM-09 invalid groupBy label name is rejected")
    void queryRange_shouldReject_invalidLabelName() {
        assertThrows(IllegalArgumentException.class,
                () -> service.queryRange("jvm_memory_used_bytes", "bad-label!", "sum", 1, 60));
        verify(webClient, never()).get();
    }

    @Test
    @DisplayName("TC-UNIT-PROM-10 blank aggregation defaults to sum; grouped PromQL is built")
    void queryRange_shouldDefaultAggToSum_andBuildGroupedPromQl() {
        stubResponse(json("{\"data\":{\"result\":[]}}"));

        var result = service.queryRange("jvm_memory_used_bytes", "area", "  ", 1, 60);

        assertEquals("sum by (area) (jvm_memory_used_bytes)", result.get("query"));
        assertTrue(((List<?>) result.get("series")).isEmpty());
    }

    @Test
    @DisplayName("TC-UNIT-PROM-11 series are parsed, named, and sorted by peak value")
    void queryRange_shouldParseSeries_sortedByPeak() {
        stubResponse(json("{\"data\":{\"result\":["
                + "{\"metric\":{\"area\":\"heap\"},\"values\":[[1690000000,\"100\"],[1690000060,\"250\"]]},"
                + "{\"metric\":{},\"values\":[[1690000000,\"300\"]]}"
                + "]}}"));

        var result = service.queryRange("jvm_memory_used_bytes", "area", "sum", 1, 60);

        List<Map<String, Object>> series = seriesOf(result);
        // peak 250 > peak 300? No - 300 is higher, so the raw series comes first
        assertEquals("jvm_memory_used_bytes", series.get(0).get("name")); // peak 300
        assertEquals("heap", series.get(1).get("name"));                  // peak 250
        List<Map<String, Object>> points = pointsOf(series.get(0));
        assertEquals(1690000000000L, points.get(0).get("t"));
        assertEquals(300.0, points.get(0).get("v"));
    }

    @Test
    @DisplayName("TC-UNIT-PROM-12 non-numeric sample values are skipped, not crashing the parse")
    void queryRange_shouldSkipNonNumericSamples() {
        // Note: "NaN" is a VALID double literal for Double.parseDouble - it must
        // be something truly unparseable to trigger the NumberFormatException path
        stubResponse(json("{\"data\":{\"result\":["
                + "{\"metric\":{},\"values\":[[1690000000,\"abc\"],[1690000060,\"42\"]]}"
                + "]}}"));

        var result = service.queryRange("m", null, "sum", 1, 60);

        List<Map<String, Object>> series = seriesOf(result);
        assertEquals(1, series.size());
        List<Map<String, Object>> points = pointsOf(series.get(0));
        assertEquals(1, points.size());
        assertEquals(42.0, points.get(0).get("v"));
    }

    @Test
    @DisplayName("TC-UNIT-PROM-13 at most 12 series are returned (MAX_SERIES cap)")
    void queryRange_shouldCapSeriesAtMax() {
        StringBuilder sb = new StringBuilder("{\"data\":{\"result\":[");
        for (int i = 0; i < 15; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"metric\":{\"s\":\"s").append(i).append("\"},\"values\":[[1690000000,\"").append(i + 1).append("\"]]}");
        }
        sb.append("]}}");
        stubResponse(json(sb.toString()));

        var result = service.queryRange("m", "s", "sum", 1, 60);

        List<Map<String, Object>> series = seriesOf(result);
        assertEquals(12, series.size());
    }

    @Test
    @DisplayName("TC-UNIT-PROM-14 identical queries are cached for 30s (second call does not re-fetch)")
    void queryRange_shouldUseCache_withinTtl() {
        stubResponse(json("{\"data\":{\"result\":[]}}"));

        service.queryRange("m", null, "sum", 1, 60);
        service.queryRange("m", null, "sum", 1, 60);

        verify(webClient, times(1)).get();
    }
}
