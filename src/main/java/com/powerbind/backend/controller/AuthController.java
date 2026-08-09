package com.powerbind.backend.controller;

import com.powerbind.backend.data.ApiResponse;
import com.powerbind.backend.data.request.AuthRequest;
import com.powerbind.backend.data.response.AuthResponse;
import com.powerbind.backend.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "User authentication — login, register, token management")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "Register a new user account")
    public ResponseEntity<ApiResponse<AuthResponse.Profile>> register(
            @Valid @RequestBody AuthRequest.Register request) {
        return ResponseEntity.ok(ApiResponse.ok("Account created", authService.register(request)));
    }

    @PostMapping("/login")
    @Operation(summary = "Login with email and password — returns access and refresh token")
    public ResponseEntity<ApiResponse<AuthResponse.TokenPair>> login(
            @Valid @RequestBody AuthRequest.Login request) {
        return ResponseEntity.ok(ApiResponse.ok("Login successful", authService.login(request)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new access token")
    public ResponseEntity<ApiResponse<AuthResponse.TokenPair>> refresh(
            @Valid @RequestBody AuthRequest.Refresh request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(request)));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke refresh token and end session")
    public ResponseEntity<ApiResponse<Void>> logout(
            @Valid @RequestBody AuthRequest.Refresh request) {
        authService.logout(request);
        return ResponseEntity.ok(ApiResponse.ok("Logged out"));
    }

    @GetMapping("/me")
    @Operation(summary = "Get current authenticated user profile")
    public ResponseEntity<ApiResponse<AuthResponse.Profile>> me(
            @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(ApiResponse.ok(authService.getProfile(email)));
    }

    @PutMapping("/profile")
    @Operation(summary = "Update display name")
    public ResponseEntity<ApiResponse<AuthResponse.Profile>> updateProfile(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody AuthRequest.UpdateProfile request) {
        return ResponseEntity.ok(ApiResponse.ok("Profile updated", authService.updateProfile(email, request)));
    }
}
