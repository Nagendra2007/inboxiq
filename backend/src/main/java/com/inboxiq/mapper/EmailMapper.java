package com.inboxiq.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inboxiq.dto.ActionItemDto;
import com.inboxiq.dto.EmailAnalysisDto;
import com.inboxiq.dto.EmailDetailDto;
import com.inboxiq.dto.EmailSummaryDto;
import com.inboxiq.dto.GeneratedReplyDto;
import com.inboxiq.entity.ActionItem;
import com.inboxiq.entity.EmailAnalysis;
import com.inboxiq.entity.EmailMessage;
import com.inboxiq.entity.GeneratedReply;
import com.inboxiq.repository.EmailSummaryRow;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns JPA entities into API-facing DTOs. Kept as one small component
 * (rather than scattering {@code toDto} methods across controllers) so a
 * raw entity — and in particular the never-log/never-expose token fields on
 * {@link com.inboxiq.entity.MailAccount}, which this mapper deliberately has
 * no method for — can never accidentally be returned from a controller.
 */
@Component
public class EmailMapper {

    private final ObjectMapper objectMapper;

    public EmailMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EmailSummaryDto toSummaryDto(EmailMessage email) {
        return new EmailSummaryDto(
                email.getId(),
                email.getSender(),
                email.getSubject(),
                email.getSnippet(),
                email.getReceivedAt(),
                email.isRead(),
                email.isHasAttachments(),
                toAnalysisDto(email.getAnalysis())
        );
    }

    /** The same summary, from the inbox list's single-query projection. */
    public EmailSummaryDto toSummaryDto(EmailSummaryRow row) {
        return new EmailSummaryDto(
                row.id(),
                row.sender(),
                row.subject(),
                row.snippet(),
                row.receivedAt(),
                row.read(),
                row.hasAttachments(),
                toAnalysisDto(row.analysis())
        );
    }

    public EmailDetailDto toDetailDto(EmailMessage email, List<ActionItem> actionItems) {
        return new EmailDetailDto(
                email.getId(),
                email.getSender(),
                email.getRecipient(),
                email.getCcRecipient(),
                email.getSubject(),
                email.getSnippet(),
                email.getBodyText(),
                email.getBodyHtml(),
                email.getReceivedAt(),
                email.isRead(),
                email.isHasAttachments(),
                email.getThreadId(),
                toAnalysisDto(email.getAnalysis()),
                actionItems.stream().map(this::toActionItemDto).toList()
        );
    }

    public EmailAnalysisDto toAnalysisDto(EmailAnalysis analysis) {
        if (analysis == null) return null;
        return new EmailAnalysisDto(
                analysis.getSummary(),
                readStringList(analysis.getKeyPointsJson()),
                analysis.getCategory() == null ? null : analysis.getCategory().name(),
                analysis.getPriority() == null ? null : analysis.getPriority().name(),
                analysis.getPriorityScore(),
                analysis.getRiskScore(),
                analysis.getRiskLevel() == null ? null : analysis.getRiskLevel().name(),
                readStringList(analysis.getRiskReasonsJson()),
                analysis.getRequiresReply(),
                analysis.getActionRequired(),
                readStringList(analysis.getImportantDatesJson()),
                analysis.getAnalysisStatus() == null ? null : analysis.getAnalysisStatus().name(),
                analysis.getFailureReason()
        );
    }

    public ActionItemDto toActionItemDto(ActionItem item) {
        return new ActionItemDto(
                item.getId(),
                item.getEmail().getId(),
                item.getEmail().getSubject(),
                item.getDescription(),
                item.getDeadline(),
                item.isCompleted()
        );
    }

    public GeneratedReplyDto toReplyDto(GeneratedReply reply) {
        return new GeneratedReplyDto(
                reply.getId(),
                reply.getEmail() != null ? reply.getEmail().getId() : null,
                reply.getUserPrompt(),
                reply.getGeneratedContent(),
                reply.getToneAdjustment(),
                reply.isSent(),
                reply.getSentAt()
        );
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
