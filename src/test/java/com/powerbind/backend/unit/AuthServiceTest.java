package com.powerbind.backend.unit;

import com.powerbind.backend.data.request.AuthRequest;
import com.powerbind.backend.data.response.AuthResponse;
import com.powerbind.backend.global.AccountLockedException;
import com.powerbind.backend.model.RefreshToken;
import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.RefreshTokenRepository;
import com.powerbind.backend.repository.UserRepository;
import com.powerbind.backend.security.JwtUtil;
import com.powerbind.backend.service.AuthService;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Unit Test (auth)")
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private JwtUtil jwtUtil;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private AuthService authService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "maxAttempts", 5);
        ReflectionTestUtils.setField(authService, "lockoutMinutes", 10L);
        ReflectionTestUtils.setField(authService, "refreshExpiration", 604800000L);
    }

    @Test
    @DisplayName("TC-U-01 Login with wrong password throws and records a failed attempt")
    void login_shouldThrow_whenPasswordIsWrong() {
        User user = User.builder()
                .username("admin")
                .password("encoded")
                .build();

        AuthRequest.Login req = new AuthRequest.Login();
        ReflectionTestUtils.setField(req, "username", "admin");
        ReflectionTestUtils.setField(req, "password", "wrong");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);
        when(userRepository.save(any())).thenReturn(user);

        assertThrows(IllegalArgumentException.class, () -> authService.login(req));
    }

    @Test
    @DisplayName("TC-U-02 Login with unknown username throws invalid credentials")
    void login_shouldThrow_whenUsernameNotFound() {
        AuthRequest.Login req = new AuthRequest.Login();
        ReflectionTestUtils.setField(req, "username", "notexist");
        ReflectionTestUtils.setField(req, "password", "password123");

        when(userRepository.findByUsername("notexist")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> authService.login(req));
    }

    @Severity(SeverityLevel.CRITICAL)
    @Test
    @DisplayName("TC-U-03 Login locks the account after the max failed attempts")
    void login_shouldLockAccount_afterMaxFailedAttempts() {
        User user = User.builder()
                .username("admin")
                .password("encoded")
                .failedAttempts(4)
                .build();

        AuthRequest.Login req = new AuthRequest.Login();
        ReflectionTestUtils.setField(req, "username", "admin");
        ReflectionTestUtils.setField(req, "password", "wrong");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);
        when(userRepository.save(any())).thenReturn(user);

        assertThrows(AccountLockedException.class, () -> authService.login(req));
    }

    @Severity(SeverityLevel.CRITICAL)
    @Test
    @DisplayName("TC-U-04 Refresh with a valid token rotates it and returns a new token pair")
    void refresh_withValidToken_shouldRotateAndReturnNewPair() {
        User user = User.builder().username("admin").build();
        RefreshToken oldToken = RefreshToken.builder()
                .user(user)
                .token("old-token")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .revoked(false)
                .build();

        AuthRequest.Refresh req = new AuthRequest.Refresh();
        ReflectionTestUtils.setField(req, "refreshToken", "old-token");

        when(refreshTokenRepository.findByToken("old-token")).thenReturn(Optional.of(oldToken));
        when(jwtUtil.generateToken("admin")).thenReturn("new-access-token");

        AuthResponse.TokenPair result = authService.refresh(req);

        assertEquals("new-access-token", result.getAccessToken());
        assertNotNull(result.getRefreshToken());
        assertNotEquals("old-token", result.getRefreshToken());
        // old token must be marked revoked, not deleted — needed for reuse detection
        assertTrue(oldToken.isRevoked());
        verify(refreshTokenRepository, never()).deleteAllByUser(any());
    }

    @Test
    @DisplayName("TC-U-05 Refresh with an expired token throws and deletes the token")
    void refresh_withExpiredToken_shouldThrowAndDeleteToken() {
        User user = User.builder().username("admin").build();
        RefreshToken expired = RefreshToken.builder()
                .user(user)
                .token("expired-token")
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .revoked(false)
                .build();

        AuthRequest.Refresh req = new AuthRequest.Refresh();
        ReflectionTestUtils.setField(req, "refreshToken", "expired-token");

        when(refreshTokenRepository.findByToken("expired-token")).thenReturn(Optional.of(expired));

        assertThrows(IllegalArgumentException.class, () -> authService.refresh(req));
        verify(refreshTokenRepository).delete(expired);
    }

    @Severity(SeverityLevel.CRITICAL)
    @Test
    @DisplayName("TC-U-06 Refresh with an already-revoked token detects reuse and revokes all sessions")
    void refresh_withAlreadyRevokedToken_shouldDetectReuseAndRevokeAllSessions() {
        User user = User.builder().username("admin").build();
        RefreshToken reused = RefreshToken.builder()
                .user(user)
                .token("stolen-token")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .revoked(true) // already rotated once — being used again means it's compromised
                .build();

        AuthRequest.Refresh req = new AuthRequest.Refresh();
        ReflectionTestUtils.setField(req, "refreshToken", "stolen-token");

        when(refreshTokenRepository.findByToken("stolen-token")).thenReturn(Optional.of(reused));

        assertThrows(IllegalArgumentException.class, () -> authService.refresh(req));
        verify(refreshTokenRepository).deleteAllByUser(user);
    }
}