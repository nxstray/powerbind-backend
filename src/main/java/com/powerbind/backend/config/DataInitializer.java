package com.powerbind.backend.config;

import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// Creates default user on startup if not already exists
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

    @Override
    public void run(String... args) {
        if (userRepository.existsByUsername(defaultUsername)) {
            return;
        }

        if (defaultPassword == null || defaultPassword.isBlank()) {
            log.warn("========================================");
            log.warn("WARNING: Default user password not set!");
            log.warn("Set APP_DEFAULT_USER_PASSWORD in .env");
            log.warn("========================================");
            return;
        }

        User user = User.builder()
                .username(defaultUsername)
                .password(passwordEncoder.encode(defaultPassword))
                .displayName("Administrator")
                .build();

        userRepository.save(user);

        log.info("========================================");
        log.info("Default user created successfully!");
        log.info("Username: {}", defaultUsername);
        log.info("========================================");
    }
}