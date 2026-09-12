package com.powerbind.backend.repository;

import com.powerbind.backend.model.Conversation;
import com.powerbind.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    // List a user's conversations, most recently updated first — powers the dropdown
    List<Conversation> findByUserOrderByUpdatedAtDesc(User user);

    // Used to verify ownership before returning/deleting a conversation
    Optional<Conversation> findByIdAndUser(UUID id, User user);
}