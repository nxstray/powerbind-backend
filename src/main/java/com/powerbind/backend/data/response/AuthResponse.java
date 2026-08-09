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
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Profile {
        private String id;
        private String email;
        private String displayName;
    }
}
