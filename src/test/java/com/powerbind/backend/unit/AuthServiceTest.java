package com.powerbind.backend.unit;

import com.powerbind.backend.data.request.AuthRequest;
import com.powerbind.backend.global.AccountLockedException;
import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.RefreshTokenRepository;
import com.powerbind.backend.repository.UserRepository;
import com.powerbind.backend.security.JwtUtil;
import com.powerbind.backend.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
    void login_shouldThrow_whenUsernameNotFound() {
        AuthRequest.Login req = new AuthRequest.Login();
        ReflectionTestUtils.setField(req, "username", "notexist");
        ReflectionTestUtils.setField(req, "password", "password123");

        when(userRepository.findByUsername("notexist")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> authService.login(req));
    }

    @Test
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
}