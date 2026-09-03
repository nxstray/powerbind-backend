package com.powerbind.backend.data.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

public class AuthRequest {

    @Getter
    @Setter
    public static class Login {
        @NotBlank(message = "Username is required")
        private String username;

        @NotBlank(message = "Password is required")
        private String password;
    }

    @Getter
    @Setter
    public static class Refresh {
        @NotBlank(message = "Refresh token is required")
        private String refreshToken;
    }

    @Getter
    @Setter
    public static class UpdateProfile {
        @NotBlank(message = "Display name is required")
        private String displayName;
    }
}