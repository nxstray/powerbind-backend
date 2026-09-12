package com.powerbind.backend.controller;

import com.powerbind.backend.data.ApiResponse;
import com.powerbind.backend.service.PrometheusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// Serves the admin Metrics page — proxies Prometheus through the same JWT +
// hasRole('ADMIN') gate as every other admin endpoint, so the browser never
// needs Prometheus' URL and the raw unauthenticated /actuator/prometheus stays
// dedicated to the Prometheus scraper container.
@RestController
@RequestMapping("/api/admin/metrics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin — Metrics", description = "JVM/system metric charts, proxied from Prometheus")
public class AdminMetricsController {

    private final PrometheusService prometheusService;

    @GetMapping("/names")
    @Operation(summary = "List available metric names — feeds the custom-metric dropdown")
    public ResponseEntity<ApiResponse<List<String>>> names() {
        return ResponseEntity.ok(ApiResponse.ok(prometheusService.getMetricNames()));
    }

    @GetMapping("/query")
    @Operation(summary = "Range-query one metric — optionally aggregated by a label")
    public ResponseEntity<ApiResponse<Map<String, Object>>> query(
            @RequestParam String metric,
            @RequestParam(required = false) String groupBy,
            @RequestParam(defaultValue = "sum") String agg,
            @RequestParam(defaultValue = "1") int hours,   // 1 | 6 | 24
            @RequestParam(defaultValue = "60") int step) { // seconds between points
        return ResponseEntity.ok(
                ApiResponse.ok(prometheusService.queryRange(metric, groupBy, agg, hours, step)));
    }
}