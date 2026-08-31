package com.powerbind.backend.repository;

import com.powerbind.backend.model.ChatMessage;
import com.powerbind.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByUserOrderByCreatedAtAsc(User user);

    void deleteByUser(User user);
}