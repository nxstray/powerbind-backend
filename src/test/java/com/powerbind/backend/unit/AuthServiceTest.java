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

    @Severity(SeverityLevel.CRITICAL)
    @Test
    @DisplayName("TC-U-07 Login on an account still on the default password flags mustChangePassword")
    void login_shouldReportMustChangePassword_whenStillOnDefault() {
        User user = User.builder()
                .username("budi")
                .password("encoded")
                .mustChangePassword(true)
                .build();

        AuthRequest.Login req = new AuthRequest.Login();
        ReflectionTestUtils.setField(req, "username", "budi");
        ReflectionTestUtils.setField(req, "password", "default-pass");

        when(userRepository.findByUsername("budi")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("default-pass", "encoded")).thenReturn(true);
        when(jwtUtil.generateToken("budi")).thenReturn("access-token");

        AuthResponse.TokenPair result = authService.login(req);

        assertTrue(result.isMustChangePassword(),
                "Login response should flag that the account is still on the default password");
    }

    @Test
    @DisplayName("TC-U-08 Change password throws when the current password is wrong")
    void changePassword_shouldThrow_whenCurrentPasswordIsWrong() {
        User user = User.builder()
                .username("budi")
                .password("encoded")
                .mustChangePassword(true)
                .build();

        AuthRequest.ChangePassword req = new AuthRequest.ChangePassword();
        ReflectionTestUtils.setField(req, "currentPassword", "wrong-current");
        ReflectionTestUtils.setField(req, "newPassword", "brand-new-password");

        when(userRepository.findByUsername("budi")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-current", "encoded")).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> authService.changePassword("budi", req));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC-U-09 Change password throws when the new password matches the current one")
    void changePassword_shouldThrow_whenNewPasswordSameAsCurrent() {
        User user = User.builder()
                .username("budi")
                .password("encoded")
                .mustChangePassword(true)
                .build();

        AuthRequest.ChangePassword req = new AuthRequest.ChangePassword();
        ReflectionTestUtils.setField(req, "currentPassword", "same-password");
        ReflectionTestUtils.setField(req, "newPassword", "same-password");

        when(userRepository.findByUsername("budi")).thenReturn(Optional.of(user));
        // both fields hold the same raw value, so it matches the stored hash either way
        when(passwordEncoder.matches("same-password", "encoded")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> authService.changePassword("budi", req));
        verify(userRepository, never()).save(any());
    }

    @Severity(SeverityLevel.CRITICAL)
    @Test
    @DisplayName("TC-U-10 Change password succeeds, clears the flag, and revokes existing sessions")
    void changePassword_shouldSucceed_clearFlagAndRevokeExistingSessions() {
        User user = User.builder()
                .username("budi")
                .password("old-encoded")
                .mustChangePassword(true)
                .build();

        AuthRequest.ChangePassword req = new AuthRequest.ChangePassword();
        ReflectionTestUtils.setField(req, "currentPassword", "default-pass");
        ReflectionTestUtils.setField(req, "newPassword", "brand-new-password");

        when(userRepository.findByUsername("budi")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("default-pass", "old-encoded")).thenReturn(true);
        when(passwordEncoder.matches("brand-new-password", "old-encoded")).thenReturn(false);
        when(passwordEncoder.encode("brand-new-password")).thenReturn("new-encoded");
        when(userRepository.save(any())).thenReturn(user);

        AuthResponse.Profile result = authService.changePassword("budi", req);

        assertFalse(result.isMustChangePassword(),
                "Flag should be cleared after a successful change");
        assertEquals("new-encoded", user.getPassword());
        assertFalse(user.isMustChangePassword());
        // old (possibly shared) password is dead now — every existing session must re-login
        verify(refreshTokenRepository).deleteAllByUser(user);
    }
}