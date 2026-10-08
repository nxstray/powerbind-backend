package com.powerbind.backend.repository;

import com.powerbind.backend.model.RefreshToken;
import com.powerbind.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByToken(String token);

    // Remove all tokens for a user on logout from all sessions
    void deleteAllByUser(User user);

    // Used by RefreshTokenCleanupService: sweep tokens whose expiry has passed.
    // Revoked-but-unexpired tokens are kept on purpose (reuse detection).
    long deleteByExpiresAtBefore(LocalDateTime cutoff);
}
