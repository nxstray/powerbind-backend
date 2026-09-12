package com.powerbind.backend.config;

import com.powerbind.backend.model.Role;
import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// Creates default/family users on startup if they don't already exist
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.default-user.username:admin}")
    private String defaultUsername;

    @Value("${app.default-user.password:}")
    private String defaultPassword;

    // Format: username:password:displayName;username2:password2:displayName2
    @Value("${app.family-users:}")
    private String familyUsers;

    @Override
    public void run(String... args) {
        if (familyUsers != null && !familyUsers.isBlank()) {
            seedFamilyUsers();
            return;
        }
        seedDefaultUser();
    }

    private void seedFamilyUsers() {
        for (String entry : familyUsers.split(";")) {
            if (entry.isBlank()) continue;
            String[] parts = entry.trim().split(":", 3);
            if (parts.length < 2) {
                log.warn("Skipping malformed app.family-users entry: {}", entry);
                continue;
            }
            String username = parts[0].trim();
            String password = parts[1].trim();
            String displayName = parts.length > 2 ? parts[2].trim() : username;

            if (userRepository.existsByUsername(username)) continue;

            userRepository.save(User.builder()
                    .username(username)
                    .password(passwordEncoder.encode(password))
                    .displayName(displayName)
                    .role(Role.USER)
                    .build());

            log.info("Family user created: {} ({})", username, displayName);
        }
    }

    private void seedDefaultUser() {
        if (userRepository.existsByUsername(defaultUsername)) {
            return;
        }

        if (defaultPassword == null || defaultPassword.isBlank()) {
            log.warn("========================================");
            log.warn("WARNING: Default user password not set!");
            log.warn("Set APP_DEFAULT_USER_PASSWORD or APP_FAMILY_USERS in .env");
            log.warn("========================================");
            return;
        }

        User user = User.builder()
                .username(defaultUsername)
                .password(passwordEncoder.encode(defaultPassword))
                .displayName("Administrator")
                .role(Role.ADMIN)
                .build();

        userRepository.save(user);

        log.info("Default user created successfully! Username: {}", defaultUsername);
    }
}