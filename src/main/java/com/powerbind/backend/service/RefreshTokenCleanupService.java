package com.powerbind.backend.service;

import com.powerbind.backend.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Rotasi refresh token di {@link AuthService} hanya menandai token lama sebagai
 * {@code revoked}, sehingga barisnya tidak pernah hilang sendiri dan tabel
 * {@code refresh_tokens} tumbuh terus. Scheduler ini menyapu token yang sudah
 * melewati {@code expiresAt}.
 *
 * <p>Token yang sudah revoked tetapi <em>belum</em> kedaluwarsa sengaja
 * dibiarkan: {@code AuthService.refresh} memakainya untuk mendeteksi pemakaian
 * ulang token curian, jadi menghapusnya lebih awal akan melemahkan deteksi itu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenCleanupService {

    private final RefreshTokenRepository refreshTokenRepository;

    // 03:30 UTC tiap hari — setelah service `backup` (02:00 UTC) selesai.
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public long cleanupExpiredTokens() {
        long deleted = refreshTokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        if (deleted > 0) {
            log.info("[TokenCleanup] {} refresh token kedaluwarsa dihapus", deleted);
        }
        return deleted;
    }
}