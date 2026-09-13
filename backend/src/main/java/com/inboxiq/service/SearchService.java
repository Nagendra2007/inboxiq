package com.inboxiq.service;

import com.inboxiq.entity.EmailCategory;
import com.inboxiq.entity.EmailMessage;
import com.inboxiq.entity.Priority;
import com.inboxiq.entity.RiskLevel;
import com.inboxiq.repository.EmailRepository;
import com.inboxiq.repository.EmailSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Backs {@code GET /api/emails/search}. Local filters (category, priority,
 * risk, unread, date range) are applied via {@link EmailSpecifications}.
 * Free-text search additionally checks subject/snippet/body locally; Gmail's
 * own search operators (from:, has:attachment, etc.) can be layered on top
 * by passing the same raw query straight into {@code GmailInboxClient.search}
 * for a "search across your whole mailbox, not just synced messages" mode —
 * left as a documented extension point since it requires an extra Gmail
 * round trip per keystroke and the spec's default is searching synced data.
 */
@Service
public class SearchService {

    private final EmailRepository emailRepository;

    public SearchService(EmailRepository emailRepository) {
        this.emailRepository = emailRepository;
    }

    public record SearchCriteria(
            String sender,
            String keyword,
            EmailCategory category,
            Priority priority,
            RiskLevel riskLevel,
            Boolean unreadOnly,
            Instant receivedFrom,
            Instant receivedTo
    ) {}

    @Transactional(readOnly = true)
    public Page<EmailMessage> search(UUID mailAccountId, SearchCriteria criteria, Pageable pageable) {
        var spec = EmailSpecifications.and(
                EmailSpecifications.forMailAccount(mailAccountId),
                EmailSpecifications.senderContains(criteria.sender()),
                EmailSpecifications.subjectOrKeywordContains(criteria.keyword()),
                EmailSpecifications.category(criteria.category()),
                EmailSpecifications.priority(criteria.priority()),
                EmailSpecifications.riskLevel(criteria.riskLevel()),
                EmailSpecifications.unreadOnly(criteria.unreadOnly()),
                EmailSpecifications.receivedBetween(criteria.receivedFrom(), criteria.receivedTo())
        );
        return emailRepository.findAll(spec, pageable);
    }
}
