package com.powerbind.backend.service;

import com.powerbind.backend.data.request.AuthRequest;
import com.powerbind.backend.data.response.AuthResponse;
import com.powerbind.backend.global.AccountLockedException;
import com.powerbind.backend.global.ResourceNotFoundException;
import com.powerbind.backend.model.RefreshToken;
import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.RefreshTokenRepository;
import com.powerbind.backend.repository.UserRepository;
import com.powerbind.backend.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    @Value("${login.max-attempts:5}")
    private int maxAttempts;

    @Value("${login.lockout-minutes:10}")
    private long lockoutMinutes;

    @Value("${jwt.refresh-expiration:604800000}")
    private long refreshExpiration;

    // Login with username and password — enforces 5-attempt lockout for 10 minutes
    @Transactional(noRollbackFor = {IllegalArgumentException.class, AccountLockedException.class})
    public AuthResponse.TokenPair login(AuthRequest.Login request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));

        // Check if account is currently locked
        if (user.getLockedUntil() != null && LocalDateTime.now().isBefore(user.getLockedUntil())) {
            long minutesLeft = java.time.Duration.between(LocalDateTime.now(), user.getLockedUntil()).toMinutes() + 1;
            throw new AccountLockedException(
                "Account locked due to too many failed attempts. Try again in " + minutesLeft + " minutes."
            );
        }

        // Validate password
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            int attempts = user.getFailedAttempts() + 1;
            user.setFailedAttempts(attempts);

            if (attempts >= maxAttempts) {
                user.setLockedUntil(LocalDateTime.now().plusMinutes(lockoutMinutes));
                user.setFailedAttempts(0);
                userRepository.save(user);
                throw new AccountLockedException(
                    "Too many failed attempts. Account locked for " + lockoutMinutes + " minutes."
                );
            }

            userRepository.save(user);
            throw new IllegalArgumentException("Invalid username or password");
        }

        // Successful login — reset failed attempts
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        String accessToken = jwtUtil.generateToken(user.getUsername());
        String refreshToken = generateRefreshToken(user);

        return AuthResponse.TokenPair.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    // Exchange a valid refresh token for a new access token
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public AuthResponse.TokenPair refresh(AuthRequest.Refresh request) {
        RefreshToken token = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        // This token reuse detection is important for security — 
        // if a refresh token is used more than once, it indicates that the token may have been compromised. 
        // In such a case, we revoke all sessions for the user to prevent unauthorized access.
        if (token.isRevoked()) {
            refreshTokenRepository.deleteAllByUser(token.getUser());
            log.warn("[Auth] Refresh token reuse terdeteksi untuk user {} — semua sesi dicabut",
                    token.getUser().getUsername());
            throw new IllegalArgumentException("Sesi tidak valid — silakan login ulang");
        }

        if (LocalDateTime.now().isAfter(token.getExpiresAt())) {
            refreshTokenRepository.delete(token);
            throw new IllegalArgumentException("Refresh token expired — please login again");
        }

        // Rolling refresh token: generate a new refresh token and revoke the old one.
        String newRefreshToken = generateRefreshToken(token.getUser());
        token.setRevoked(true);
        refreshTokenRepository.save(token);

        String newAccessToken = jwtUtil.generateToken(token.getUser().getUsername());

        return AuthResponse.TokenPair.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();
    }

    // Revoke refresh token on logout
    @Transactional
    public void logout(AuthRequest.Refresh request) {
        refreshTokenRepository.findByToken(request.getRefreshToken())
                .ifPresent(refreshTokenRepository::delete);
    }

    // Get current user profile
    public AuthResponse.Profile getProfile(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return AuthResponse.Profile.builder()
                .id(user.getId().toString())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .build();
    }

    // Update display name
    @Transactional
    public AuthResponse.Profile updateProfile(String username, AuthRequest.UpdateProfile request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        user.setDisplayName(request.getDisplayName());
        userRepository.save(user);

        return AuthResponse.Profile.builder()
                .id(user.getId().toString())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .build();
    }

    // Generate and persist a new refresh token for a user
    private String generateRefreshToken(User user) {
        String tokenValue = UUID.randomUUID().toString();

        RefreshToken token = RefreshToken.builder()
                .user(user)
                .token(tokenValue)
                .expiresAt(LocalDateTime.now().plusSeconds(refreshExpiration / 1000))
                .build();

        refreshTokenRepository.save(token);
        return tokenValue;
    }
}