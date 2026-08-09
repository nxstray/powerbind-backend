package com.powerbind.backend.data.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

public class AuthRequest {

    @Getter
    @Setter
    public static class Login {
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        private String email;

        @NotBlank(message = "Password is required")
        private String password;
    }

    @Getter
    @Setter
    public static class Register {
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        private String email;

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        private String password;

        @NotBlank(message = "Display name is required")
        private String displayName;
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
