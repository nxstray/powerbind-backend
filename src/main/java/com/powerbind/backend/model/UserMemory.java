package com.powerbind.backend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

// A single durable fact/preference the AI agent has learned about a user,
// e.g. "biasa pulang kerja jam 6 sore" or "prioritaskan hemat listrik di kamar anak".
// Reused across every conversation, unlike ChatMessage which is scoped to one thread.
@Entity
@Table(name = "user_memories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 300)
    private String content;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}