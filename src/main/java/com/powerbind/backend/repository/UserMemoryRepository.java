package com.powerbind.backend.repository;

import com.powerbind.backend.model.User;
import com.powerbind.backend.model.UserMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserMemoryRepository extends JpaRepository<UserMemory, UUID> {

    // Most recent first — shown in the memory panel and injected into the system prompt
    List<UserMemory> findByUserOrderByCreatedAtDesc(User user);

    // Used to verify ownership before deleting a single memory
    Optional<UserMemory> findByIdAndUser(UUID id, User user);

    void deleteByUser(User user);

    long countByUser(User user);
}