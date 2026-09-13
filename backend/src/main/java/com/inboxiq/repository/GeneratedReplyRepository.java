package com.inboxiq.repository;

import com.inboxiq.entity.GeneratedReply;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GeneratedReplyRepository extends JpaRepository<GeneratedReply, UUID> {
    List<GeneratedReply> findByEmailIdOrderByCreatedAtDesc(UUID emailId);
}
