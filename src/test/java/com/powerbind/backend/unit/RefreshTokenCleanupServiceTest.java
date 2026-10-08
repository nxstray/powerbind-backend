package com.powerbind.backend.unit;

import com.powerbind.backend.repository.RefreshTokenRepository;
import com.powerbind.backend.service.RefreshTokenCleanupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Unit Test (token cleanup)")
@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupServiceTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks private RefreshTokenCleanupService refreshTokenCleanupService;

    @Test
    @DisplayName("TC-UNIT-TOKENCLEANUP-01 expired tokens are deleted and the count is reported")
    void cleanupExpiredTokens_shouldDeleteExpiredTokens() {
        when(refreshTokenRepository.deleteByExpiresAtBefore(any(LocalDateTime.class))).thenReturn(3L);

        long deleted = refreshTokenCleanupService.cleanupExpiredTokens();

        assertEquals(3L, deleted);

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(refreshTokenRepository).deleteByExpiresAtBefore(cutoff.capture());
        // The cutoff must be "now" — a tolerance keeps this from racing the clock.
        long driftSeconds = Math.abs(ChronoUnit.SECONDS.between(cutoff.getValue(), LocalDateTime.now()));
        assertTrue(driftSeconds <= 5, "cutoff drifted " + driftSeconds + "s from now");
    }

    @Test
    @DisplayName("TC-UNIT-TOKENCLEANUP-02 a sweep with nothing to delete reports zero")
    void cleanupExpiredTokens_shouldReportZeroWhenNothingExpired() {
        when(refreshTokenRepository.deleteByExpiresAtBefore(any(LocalDateTime.class))).thenReturn(0L);

        assertEquals(0L, refreshTokenCleanupService.cleanupExpiredTokens());
        verify(refreshTokenRepository, times(1)).deleteByExpiresAtBefore(any(LocalDateTime.class));
    }
}