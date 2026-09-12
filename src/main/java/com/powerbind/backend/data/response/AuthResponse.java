package com.powerbind.backend.data.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

public class AuthResponse {

    @Getter
    @Builder
    @AllArgsConstructor
    public static class TokenPair {
        private String accessToken;
        private String refreshToken;
        // Tells the frontend to block navigation and force the change-password
        // modal — true until the user replaces the shared default password.
        private boolean mustChangePassword;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Profile {
        private String id;
        private String username;
        private String displayName;
        private boolean mustChangePassword;
        // "USER" or "ADMIN" — frontend uses this to show the ERD/Log nav items
        // and to gate the /erd and /log routes client-side (server-side gating
        // still lives in AdminErdController / AdminLogController).
        private String role;
    }
}