package com.powerbind.backend.repository;

import com.powerbind.backend.model.RefreshToken;
import com.powerbind.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByToken(String token);

    // Remove all tokens for a user on logout from all sessions
    void deleteAllByUser(User user);
}
