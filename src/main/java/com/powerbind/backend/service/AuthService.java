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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

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

    // Register a new user — email must be unique
    @Transactional
    public AuthResponse.Profile register(AuthRequest.Register request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already registered");
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .displayName(request.getDisplayName())
                .build();

        user = userRepository.save(user);

        return AuthResponse.Profile.builder()
                .id(user.getId().toString())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .build();
    }

    // Login with email and password — enforces 5-attempt lockout for 10 minutes
    @Transactional
    public AuthResponse.TokenPair login(AuthRequest.Login request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

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
                // Lock the account for lockoutMinutes
                user.setLockedUntil(LocalDateTime.now().plusMinutes(lockoutMinutes));
                user.setFailedAttempts(0);
                userRepository.save(user);
                throw new AccountLockedException(
                    "Too many failed attempts. Account locked for " + lockoutMinutes + " minutes."
                );
            }

            userRepository.save(user);
            throw new IllegalArgumentException("Invalid email or password");
        }

        // Successful login — reset failed attempts
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        String accessToken = jwtUtil.generateToken(user.getEmail());
        String refreshToken = generateRefreshToken(user);

        return AuthResponse.TokenPair.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    // Exchange a valid refresh token for a new access token
    @Transactional
    public AuthResponse.TokenPair refresh(AuthRequest.Refresh request) {
        RefreshToken token = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        if (LocalDateTime.now().isAfter(token.getExpiresAt())) {
            refreshTokenRepository.delete(token);
            throw new IllegalArgumentException("Refresh token expired — please login again");
        }

        // Rotate refresh token on every use for security
        refreshTokenRepository.delete(token);
        String newRefreshToken = generateRefreshToken(token.getUser());
        String newAccessToken = jwtUtil.generateToken(token.getUser().getEmail());

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
    public AuthResponse.Profile getProfile(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return AuthResponse.Profile.builder()
                .id(user.getId().toString())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .build();
    }

    // Update display name
    @Transactional
    public AuthResponse.Profile updateProfile(String email, AuthRequest.UpdateProfile request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        user.setDisplayName(request.getDisplayName());
        userRepository.save(user);

        return AuthResponse.Profile.builder()
                .id(user.getId().toString())
                .email(user.getEmail())
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
                .createdAt(LocalDateTime.now())
                .build();

        refreshTokenRepository.save(token);
        return tokenValue;
    }
}
