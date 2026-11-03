package com.mailroom.repository;

import com.mailroom.entity.ChatConversation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {

    List<ChatConversation> findByUserIdOrderByUpdatedAtDesc(UUID userId, Pageable pageable);

    /**
     * Always by id <em>and</em> user. A conversation id is a UUID and hard
     * to guess, but "hard to guess" is not an access control: one query
     * that forgets the owner is all it takes to read somebody else's
     * questions about their own mail.
     */
    Optional<ChatConversation> findByIdAndUserId(UUID id, UUID userId);
}
