package com.powerbind.backend.controller;

import com.powerbind.backend.data.ApiResponse;
import com.powerbind.backend.data.request.RoomRequest;
import com.powerbind.backend.data.response.RoomResponse;
import com.powerbind.backend.service.RoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
@Tag(name = "Rooms", description = "Manage smart home rooms and their device status")
public class RoomController {

    private final RoomService roomService;

    @GetMapping
    @Operation(summary = "Get all rooms with live presence and relay status")
    public ResponseEntity<ApiResponse<List<RoomResponse.Detail>>> getAllRooms() {
        return ResponseEntity.ok(ApiResponse.ok(roomService.getAllRooms()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single room by ID")
    public ResponseEntity<ApiResponse<RoomResponse.Detail>> getRoomById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(roomService.getRoomById(id)));
    }

    @PostMapping
    @Operation(summary = "Create a new room")
    public ResponseEntity<ApiResponse<RoomResponse.Detail>> createRoom(
            @Valid @RequestBody RoomRequest.Create request) {
        return ResponseEntity.ok(ApiResponse.ok("Room created", roomService.createRoom(request)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update room name")
    public ResponseEntity<ApiResponse<RoomResponse.Detail>> updateRoom(
            @PathVariable UUID id,
            @Valid @RequestBody RoomRequest.Update request) {
        return ResponseEntity.ok(ApiResponse.ok("Room updated", roomService.updateRoom(id, request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a room")
    public ResponseEntity<ApiResponse<Void>> deleteRoom(@PathVariable UUID id) {
        roomService.deleteRoom(id);
        return ResponseEntity.ok(ApiResponse.ok("Room deleted"));
    }
}
