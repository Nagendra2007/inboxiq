package com.mailroom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailroom.ai.AiClient;
import com.mailroom.ai.AiConfig;
import com.mailroom.ai.AiResponseParser;
import com.mailroom.ai.dto.ChatAnswerAiResult;
import com.mailroom.ai.dto.ChatPlanAiResult;
import com.mailroom.ai.prompt.ChatAnswerPrompt;
import com.mailroom.ai.prompt.ChatPlanPrompt;
import com.mailroom.ai.prompt.PromptPair;
import com.mailroom.entity.ChatConversation;
import com.mailroom.entity.ChatMessage;
import com.mailroom.entity.ChatRole;
import com.mailroom.entity.EmailAnalysis;
import com.mailroom.entity.EmailCategory;
import com.mailroom.entity.EmailMessage;
import com.mailroom.entity.User;
import com.mailroom.repository.ChatConversationRepository;
import com.mailroom.repository.ChatMessageRepository;
import com.mailroom.repository.EmailRepository;
import com.mailroom.repository.EmailSpecifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Answering questions about a mailbox.
 *
 * <p>Three steps, and the division between them is the point. The model
 * <b>plans</b> a search from the question alone, having seen no email at
 * all. The database <b>retrieves</b>. The model then <b>answers</b> from
 * what came back, with no ability to do anything but produce text.
 *
 * <p>Email is the most attacker-reachable input this application has:
 * anyone who can send the user a message can put text into the answering
 * prompt. Keeping retrieval out of the model's hands is what stops an
 * injected instruction from widening its own search, and verifying the
 * citations is what stops it from inventing a source.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** How many emails the answering model is shown. */
    private static final int CANDIDATES = 24;

    /**
     * How many the database returns before ranking.
     *
     * <p>Wider than what is shown, because semantic ranking can tell a
     * genuine match from an incidental one and keyword search cannot. With
     * no embeddings configured this only costs a slightly larger query —
     * the extra rows are trimmed by recency, exactly as before.
     */
    private static final int RETRIEVED = 80;

    /** Turns of history given to the planner, newest last. */
    private static final int HISTORY_TURNS = 6;

    /** A summary long enough to answer from, short enough to send 24 of. */
    private static final int SUMMARY_CHARS = 400;

    private final EmailRepository emailRepository;
    private final ChatConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final AiClient aiClient;
    private final AiResponseParser parser;
    private final AiSettingsService aiSettingsService;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    public ChatService(EmailRepository emailRepository,
                       ChatConversationRepository conversations,
                       ChatMessageRepository messages,
                       AiClient aiClient,
                       AiResponseParser parser,
                       AiSettingsService aiSettingsService,
                       EmbeddingService embeddingService,
                       ObjectMapper objectMapper) {
        this.emailRepository = emailRepository;
        this.conversations = conversations;
        this.messages = messages;
        this.aiClient = aiClient;
        this.parser = parser;
        this.aiSettingsService = aiSettingsService;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
    }

    /** One email as an answer cites it. */
    public record Citation(UUID id, String sender, String subject, Instant receivedAt, String snippet) {}

    public record Answer(UUID conversationId, String answer, List<Citation> citations, int searched) {}

    /**
     * Asks one question.
     *
     * @param conversationId an existing conversation to continue, or null to start one
     */
    @Transactional
    public Answer ask(User user, UUID mailAccountId, UUID conversationId, String question) {
        ChatConversation conversation = conversationFor(user, conversationId, question);
        List<ChatMessage> turns = previousTurns(conversation);

        // The planner gets only what the user themselves typed. An
        // assistant turn is derived from email content, and feeding it back
        // into a prompt with no injection guard would be a way for an email
        // to influence the next search — laundered through an answer, but
        // influence all the same. The answering prompt, which is guarded,
        // sees both sides.
        ChatPlanAiResult plan = plan(question, asLines(turns, true));
        List<EmailMessage> candidates = retrieve(mailAccountId, plan);

        // Keyword search decided what is eligible; this decides what is
        // relevant, and only the top two dozen are ever shown.
        candidates = embeddingService.rerank(question, candidates);
        if (candidates.size() > CANDIDATES) candidates = candidates.subList(0, CANDIDATES);

        // "Which of those is the latest?" is about the last answer's
        // emails, not about a fresh search. Carrying them forward is what
        // makes a second question a conversation rather than a reset.
        candidates = withPrevious(candidates, lastCitations(turns));

        record(conversation, ChatRole.USER, question, List.of());

        if (candidates.isEmpty()) {
            String nothing = "Nothing in your mail matches that. It may be older than what has been "
                    + "imported — Settings can fetch more history.";
            record(conversation, ChatRole.ASSISTANT, nothing, List.of());
            return new Answer(conversation.getId(), nothing, List.of(), 0);
        }

        ChatAnswerAiResult result = answer(question, candidates, asLines(turns, false));
        List<EmailMessage> cited = verifyCitations(result.emailIds(), candidates);

        record(conversation, ChatRole.ASSISTANT, result.answer(),
                cited.stream().map(EmailMessage::getId).toList());

        return new Answer(conversation.getId(), result.answer(), cited.stream().map(this::toCitation).toList(),
                candidates.size());
    }

    private ChatPlanAiResult plan(String question, List<String> history) {
        PromptPair prompt = ChatPlanPrompt.build(question, history);
        AiConfig ai = aiSettingsService.current();
        return parser.parseChatPlan(
                aiClient.complete(ai, prompt.systemPrompt(), prompt.userPrompt(), true));
    }

    private ChatAnswerAiResult answer(String question, List<EmailMessage> candidates, List<String> history) {
        List<ChatAnswerPrompt.Candidate> shown = candidates.stream()
                .map(email -> new ChatAnswerPrompt.Candidate(
                        email.getId().toString(),
                        email.getSender(),
                        email.getReceivedAt() == null ? null : email.getReceivedAt().toString(),
                        email.getSubject(),
                        summaryOf(email)))
                .toList();

        PromptPair prompt = ChatAnswerPrompt.build(question, shown, history);
        AiConfig ai = aiSettingsService.current();
        return parser.parseChatAnswer(
                aiClient.complete(ai, prompt.systemPrompt(), prompt.userPrompt(), true));
    }

    /**
     * The AI summary when there is one, the email's own text when there is
     * not.
     *
     * <p>Imported history is stored unanalysed, so most of what a question
     * reaches has no summary at all — and waiting to analyse two dozen
     * emails before answering would turn a question into a minute. The
     * snippet and the opening of the body are enough to tell whether an
     * email is the fee receipt.
     */
    private String summaryOf(EmailMessage email) {
        EmailAnalysis analysis = email.getAnalysis();
        if (analysis != null && analysis.getSummary() != null && !analysis.getSummary().isBlank()) {
            return clip(analysis.getSummary());
        }
        if (email.getSnippet() != null && !email.getSnippet().isBlank()) {
            return clip(email.getSnippet());
        }
        return clip(email.getBodyText());
    }

    private static String clip(String text) {
        if (text == null) return "";
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= SUMMARY_CHARS ? collapsed : collapsed.substring(0, SUMMARY_CHARS) + "…";
    }

    /**
     * Runs the planned search.
     *
     * <p>Two passes, because a category is the most useful filter when it
     * is right and the most destructive when it is wrong: if the narrow
     * search finds nothing, the same keywords are tried without it rather
     * than reporting that the mail does not exist.
     */
    private List<EmailMessage> retrieve(UUID mailAccountId, ChatPlanAiResult plan) {
        List<EmailMessage> narrow = search(mailAccountId, plan, true);
        if (!narrow.isEmpty() || plan.category() == null) return narrow;

        log.debug("Chat search found nothing in category {}; retrying without it", plan.category());
        return search(mailAccountId, plan, false);
    }

    private List<EmailMessage> search(UUID mailAccountId, ChatPlanAiResult plan, boolean withCategory) {
        Specification<EmailMessage> scope = EmailSpecifications.and(
                // Scoped to this mailbox first and always. Everything below
                // narrows; nothing below can widen past this.
                EmailSpecifications.forMailAccount(mailAccountId),
                // Tolerant of mail that has no category yet, which is most
                // of an imported mailbox: not knowing what something is
                // must not be the same as knowing it is something else.
                withCategory ? EmailSpecifications.categoryOrUnknown(categoryOf(plan)) : null,
                EmailSpecifications.receivedBetween(dateOf(plan.after()), dateOf(plan.before())),
                plan.unreadOnly() ? EmailSpecifications.unreadOnly(true) : null
        );

        // Subject matches first, and kept. Someone asking about the
        // academic calendar wants the email called "Academic Calendar",
        // and the body search below is capped and ordered by date — so
        // without this a two-year-old subject match loses its place to
        // this week's mail that mentions the word in a footer.
        List<EmailMessage> found = new ArrayList<>(emailRepository.findAll(
                EmailSpecifications.and(scope, EmailSpecifications.subjectContainsAny(plan.keywords())),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "receivedAt"))).getContent());

        found.addAll(emailRepository.findAll(
                EmailSpecifications.and(scope, EmailSpecifications.anyKeyword(plan.keywords())),
                PageRequest.of(0, RETRIEVED, Sort.by(Sort.Direction.DESC, "receivedAt"))).getContent());

        // Senders are matched separately and merged: "receipts from the
        // bank" means either, not both, and requiring both finds nothing
        // when the bank's address does not contain the word "bank".
        if (plan.senders() != null && !plan.senders().isEmpty() && found.size() < RETRIEVED) {
            for (String sender : plan.senders().stream().limit(3).toList()) {
                Specification<EmailMessage> bySender = EmailSpecifications.and(
                        EmailSpecifications.forMailAccount(mailAccountId),
                        EmailSpecifications.senderContains(sender),
                        EmailSpecifications.receivedBetween(dateOf(plan.after()), dateOf(plan.before())));
                found.addAll(emailRepository.findAll(bySender,
                        PageRequest.of(0, 8, Sort.by(Sort.Direction.DESC, "receivedAt"))).getContent());
            }
        }

        Set<UUID> seen = new LinkedHashSet<>();
        return found.stream()
                .filter(email -> seen.add(email.getId()))
                .limit(RETRIEVED)
                .toList();
    }

    private static EmailCategory categoryOf(ChatPlanAiResult plan) {
        if (plan.category() == null) return null;
        try {
            return EmailCategory.valueOf(plan.category());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant dateOf(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return null;
        try {
            return LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Keeps only citations that were actually in front of the model.
     *
     * <p>A model asked for ids will sometimes produce well-formed ones it
     * invented, and an invented citation is worse than no citation: it
     * looks like evidence. The model decides which of these emails answer
     * the question; it is never trusted with what the emails are.
     */
    static List<EmailMessage> verifyCitations(List<String> claimed, List<EmailMessage> candidates) {
        if (claimed == null || claimed.isEmpty()) return List.of();
        Map<String, EmailMessage> byId = candidates.stream()
                .collect(Collectors.toMap(email -> email.getId().toString(), email -> email, (a, b) -> a));

        List<EmailMessage> verified = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : claimed) {
            if (id == null) continue;
            EmailMessage email = byId.get(id.trim().toLowerCase(Locale.ROOT));
            if (email == null) email = byId.get(id.trim());
            if (email != null && seen.add(email.getId().toString())) verified.add(email);
            if (verified.size() == 8) break;
        }
        return verified;
    }

    private Citation toCitation(EmailMessage email) {
        return new Citation(email.getId(), email.getSender(), email.getSubject(),
                email.getReceivedAt(), clip(email.getSnippet()));
    }

    private ChatConversation conversationFor(User user, UUID conversationId, String question) {
        if (conversationId != null) {
            Optional<ChatConversation> existing =
                    conversations.findByIdAndUserId(conversationId, user.getId());
            if (existing.isPresent()) return existing.get();
        }
        ChatConversation fresh = new ChatConversation();
        fresh.setUser(user);
        fresh.setTitle(title(question));
        return conversations.save(fresh);
    }

    private static String title(String question) {
        if (question == null || question.isBlank()) return "New conversation";
        String trimmed = question.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 77) + "…";
    }

    private List<ChatMessage> previousTurns(ChatConversation conversation) {
        if (conversation.getId() == null) return List.of();
        List<ChatMessage> all = messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        return all.stream().skip(Math.max(0, all.size() - HISTORY_TURNS)).toList();
    }

    /** @param questionsOnly true to leave out answers, which carry email content */
    private List<String> asLines(List<ChatMessage> turns, boolean questionsOnly) {
        return turns.stream()
                .filter(message -> !questionsOnly || message.getRole() == ChatRole.USER)
                .map(message -> (message.getRole() == ChatRole.USER ? "asked: " : "answered: ")
                        + clip(message.getContent()))
                .toList();
    }

    /** The emails the previous answer cited, so a follow-up can mean "those". */
    private List<EmailMessage> lastCitations(List<ChatMessage> turns) {
        for (int i = turns.size() - 1; i >= 0; i--) {
            ChatMessage message = turns.get(i);
            if (message.getRole() != ChatRole.ASSISTANT || message.getCitedEmailIdsJson() == null) continue;
            try {
                List<String> ids = objectMapper.readValue(
                        message.getCitedEmailIdsJson(), new TypeReference<List<String>>() {});
                return ids.stream()
                        .map(id -> emailRepository.findById(UUID.fromString(id)).orElse(null))
                        .filter(java.util.Objects::nonNull)
                        .toList();
            } catch (Exception e) {
                return List.of();
            }
        }
        return List.of();
    }

    /**
     * Puts the previous answer's emails in front of this search's results.
     *
     * <p>In front, because a follow-up is usually about them; not
     * instead, because it might not be, and a question that has moved on
     * should not keep being answered from the last one's mail.
     */
    private static List<EmailMessage> withPrevious(List<EmailMessage> found, List<EmailMessage> previous) {
        if (previous.isEmpty()) return found;
        Set<UUID> seen = new LinkedHashSet<>();
        List<EmailMessage> merged = new ArrayList<>();
        for (EmailMessage email : previous) {
            if (seen.add(email.getId())) merged.add(email);
        }
        for (EmailMessage email : found) {
            if (seen.add(email.getId())) merged.add(email);
        }
        return merged.size() <= CANDIDATES ? merged : merged.subList(0, CANDIDATES);
    }

    private void record(ChatConversation conversation, ChatRole role, String content, List<UUID> cited) {
        ChatMessage message = new ChatMessage();
        message.setConversation(conversation);
        message.setRole(role);
        message.setContent(content);
        if (!cited.isEmpty()) {
            try {
                message.setCitedEmailIdsJson(objectMapper.writeValueAsString(
                        cited.stream().map(UUID::toString).toList()));
            } catch (Exception e) {
                log.debug("Could not store citations: {}", e.getMessage());
            }
        }
        messages.save(message);
        conversations.save(conversation);
    }

    @Transactional(readOnly = true)
    public List<ChatConversation> list(UUID userId) {
        return conversations.findByUserIdOrderByUpdatedAtDesc(userId, PageRequest.of(0, 30));
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> history(UUID userId, UUID conversationId) {
        return conversations.findByIdAndUserId(conversationId, userId)
                .map(conversation -> messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId()))
                .orElse(List.of());
    }

    /** Resolves stored citation ids back to emails, for reopening a conversation. */
    @Transactional(readOnly = true)
    public List<Citation> citationsOf(ChatMessage message) {
        if (message.getCitedEmailIdsJson() == null) return List.of();
        try {
            List<String> ids = objectMapper.readValue(
                    message.getCitedEmailIdsJson(), new TypeReference<List<String>>() {});
            return ids.stream()
                    .map(id -> emailRepository.findById(UUID.fromString(id)).orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .map(this::toCitation)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Transactional
    public void delete(UUID userId, UUID conversationId) {
        conversations.findByIdAndUserId(conversationId, userId).ifPresent(conversations::delete);
    }
}
