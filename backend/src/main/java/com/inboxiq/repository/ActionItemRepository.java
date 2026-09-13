package com.inboxiq.repository;

import com.inboxiq.entity.ActionItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ActionItemRepository extends JpaRepository<ActionItem, UUID> {

    Page<ActionItem> findByEmail_MailAccountIdOrderByDeadlineAsc(UUID mailAccountId, Pageable pageable);

    List<ActionItem> findByEmail_MailAccountIdAndCompletedFalseAndDeadlineBetweenOrderByDeadlineAsc(
            UUID mailAccountId, LocalDate from, LocalDate to);

    long countByEmail_MailAccountIdAndCompletedFalse(UUID mailAccountId);

    /** Used when re-analyzing an email, to refresh its extracted action items. */
    List<ActionItem> findByEmailId(UUID emailId);

    /** Re-analysis only replaces items the user hasn't already completed. */
    void deleteByEmailIdAndCompletedFalse(UUID emailId);
}
