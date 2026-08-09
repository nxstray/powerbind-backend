package com.powerbind.backend.controller;

import com.powerbind.backend.data.ApiResponse;
import com.powerbind.backend.data.response.DashboardResponse;
import com.powerbind.backend.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Dashboard summary and chart data")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    @Operation(summary = "Get dashboard summary — room count, power usage, estimated cost")
    public ResponseEntity<ApiResponse<DashboardResponse.Summary>> getSummary() {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getSummary()));
    }

    @GetMapping("/power-history")
    @Operation(summary = "Get power history for chart — default last 24 hours")
    public ResponseEntity<ApiResponse<List<DashboardResponse.PowerHistory>>> getPowerHistory(
            @RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getPowerHistory(hours)));
    }
}
