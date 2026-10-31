package com.mailroom.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailroom.dto.ActionItemDto;
import com.mailroom.dto.EmailAnalysisDto;
import com.mailroom.dto.EmailDetailDto;
import com.mailroom.dto.EmailSummaryDto;
import com.mailroom.dto.GeneratedReplyDto;
import com.mailroom.entity.ActionItem;
import com.mailroom.entity.EmailAnalysis;
import com.mailroom.entity.EmailMessage;
import com.mailroom.entity.GeneratedReply;
import com.mailroom.repository.EmailSummaryRow;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns JPA entities into API-facing DTOs. Kept as one small component
 * (rather than scattering {@code toDto} methods across controllers) so a
 * raw entity — and in particular the never-log/never-expose token fields on
 * {@link com.mailroom.entity.MailAccount}, which this mapper deliberately has
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
                email.isArchived(),
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
                row.archived(),
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
                email.isArchived(),
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
