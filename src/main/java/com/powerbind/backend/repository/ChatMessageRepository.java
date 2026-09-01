package com.powerbind.backend.repository;

import com.powerbind.backend.model.ChatMessage;
import com.powerbind.backend.model.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    // Fetch all messages within a single conversation, oldest first — powers conversation view
    List<ChatMessage> findByConversationOrderByCreatedAtAsc(Conversation conversation);
}