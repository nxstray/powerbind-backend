package com.powerbind.backend.repository;

import com.powerbind.backend.model.ChatMessage;
import com.powerbind.backend.model.Conversation;
import com.powerbind.backend.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    // Fetch all messages within a single conversation, oldest first — powers conversation view
    List<ChatMessage> findByConversationOrderByCreatedAtAsc(Conversation conversation);

    // Most recent messages across ALL of a user's conversations, newest first — feeds the
    // memory extractor a bounded window of the user's full history instead of just one turn.
    // Window size is controlled by the caller via Pageable (see MemoryService.HISTORY_WINDOW).
    List<ChatMessage> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);
}