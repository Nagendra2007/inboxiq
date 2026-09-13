package com.inboxiq.controller;

import com.inboxiq.dto.AdjustReplyRequest;
import com.inboxiq.dto.EmailAnalysisDto;
import com.inboxiq.dto.EmailDetailDto;
import com.inboxiq.dto.EmailSummaryDto;
import com.inboxiq.dto.GenerateReplyRequest;
import com.inboxiq.dto.GeneratedReplyDto;
import com.inboxiq.dto.SendReplyRequest;
import com.inboxiq.dto.ThreadMessageDto;
import com.inboxiq.entity.EmailCategory;
import com.inboxiq.entity.EmailMessage;
import com.inboxiq.entity.GeneratedReply;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.Priority;
import com.inboxiq.entity.RiskLevel;
import com.inboxiq.entity.User;
import com.inboxiq.exception.ResourceNotFoundException;
import com.inboxiq.gmail.GmailInboxClient;
import com.inboxiq.gmail.ParsedGmailMessage;
import com.inboxiq.mapper.EmailMapper;
import com.inboxiq.repository.ActionItemRepository;
import com.inboxiq.repository.EmailRepository;
import com.inboxiq.security.CurrentUserProvider;
import com.inboxiq.service.AssistantCommandService;
import com.inboxiq.service.EmailAnalysisService;
import com.inboxiq.service.MailAccountService;
import com.inboxiq.service.RateLimiterService;
import com.inboxiq.service.ReplyService;
import com.inboxiq.service.SearchService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inbox, search, analysis, and reply endpoints. Every method that takes an
 * {@code emailId} re-verifies the email belongs to the caller's own mail
 * account before touching it — {@link ResourceNotFoundException} either way,
 * so a request for someone else's email id is indistinguishable from a
 * request for one that doesn't exist.
 */
@RestController
@RequestMapping("/api/emails")
public class EmailController {

    private final CurrentUserProvider currentUserProvider;
    private final MailAccountService mailAccountService;
    private final EmailRepository emailRepository;
    private final ActionItemRepository actionItemRepository;
    private final EmailMapper emailMapper;
    private final EmailAnalysisService emailAnalysisService;
    private final SearchService searchService;
    private final ReplyService replyService;
    private final AssistantCommandService assistantCommandService;
    private final GmailInboxClient gmailInboxClient;
    private final RateLimiterService rateLimiterService;

    public EmailController(CurrentUserProvider currentUserProvider,
                            MailAccountService mailAccountService,
                            EmailRepository emailRepository,
                            ActionItemRepository actionItemRepository,
                            EmailMapper emailMapper,
                            EmailAnalysisService emailAnalysisService,
                            SearchService searchService,
                            ReplyService replyService,
                            AssistantCommandService assistantCommandService,
                            GmailInboxClient gmailInboxClient,
                            RateLimiterService rateLimiterService) {
        this.currentUserProvider = currentUserProvider;
        this.mailAccountService = mailAccountService;
        this.emailRepository = emailRepository;
        this.actionItemRepository = actionItemRepository;
        this.emailMapper = emailMapper;
        this.emailAnalysisService = emailAnalysisService;
        this.searchService = searchService;
        this.replyService = replyService;
        this.assistantCommandService = assistantCommandService;
        this.gmailInboxClient = gmailInboxClient;
        this.rateLimiterService = rateLimiterService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Page<EmailSummaryDto> list(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        MailAccount account = currentAccount();
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return emailRepository.findByMailAccountIdOrderByReceivedAtDesc(account.getId(), pageable)
                .map(emailMapper::toSummaryDto);
    }

    @GetMapping("/search")
    @Transactional(readOnly = true)
    public Page<EmailSummaryDto> search(
            @RequestParam(required = false) String sender,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) EmailCategory category,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) RiskLevel riskLevel,
            @RequestParam(required = false) Boolean unreadOnly,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        MailAccount account = currentAccount();
        var criteria = new SearchService.SearchCriteria(sender, keyword, category, priority, riskLevel, unreadOnly, from, to);
        Pageable pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("receivedAt").descending());
        return searchService.search(account.getId(), criteria, pageable).map(emailMapper::toSummaryDto);
    }

    @GetMapping("/{id}")
    @Transactional
    public EmailDetailDto get(@PathVariable UUID id) {
        EmailMessage email = ownedEmailOrThrow(id);
        if (!email.isRead()) {
            email.setRead(true);
            emailRepository.save(email);
        }
        List<com.inboxiq.entity.ActionItem> items = actionItemRepository.findByEmailId(email.getId());
        return emailMapper.toDetailDto(email, items);
    }

    /**
     * Deletes one email from InboxIQ and moves it to Trash in the user's
     * real Gmail (reversible there, same as Gmail's own trash icon) — not a
     * permanent Gmail delete. The Gmail side is done first: if it fails
     * (expired auth, Gmail unavailable), nothing is deleted locally either,
     * so InboxIQ's copy and the real inbox never drift out of sync. Also
     * removes everything derived from the email (its analysis, action
     * items, and reply drafts — all cascade at the database level via ON
     * DELETE CASCADE).
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID id) {
        EmailMessage email = ownedEmailOrThrow(id);
        gmailInboxClient.trashMessage(email.getMailAccount(), email.getProviderMessageId());
        emailRepository.delete(email);
    }

    @GetMapping("/{id}/analysis")
    @Transactional(readOnly = true)
    public EmailAnalysisDto analysis(@PathVariable UUID id) {
        EmailMessage email = ownedEmailOrThrow(id);
        return emailMapper.toAnalysisDto(email.getAnalysis());
    }

    /** Forces a fresh analysis pass, e.g. after the user edits/re-checks an email. */
    @PostMapping("/{id}/analyze")
    @Transactional
    public EmailAnalysisDto analyze(@PathVariable UUID id) {
        User user = currentUserProvider.getCurrentUser();
        EmailMessage email = ownedEmailOrThrow(id);
        rateLimiterService.checkAiRequest(user.getId());
        return emailMapper.toAnalysisDto(emailAnalysisService.analyze(email));
    }

    /** Live Gmail thread view (not the locally synced copy) so replies sent moments ago already show up. */
    @GetMapping("/{id}/thread")
    @Transactional(readOnly = true)
    public List<ThreadMessageDto> thread(@PathVariable UUID id) {
        EmailMessage email = ownedEmailOrThrow(id);
        MailAccount account = email.getMailAccount();
        if (email.getThreadId() == null) {
            return List.of(toThreadDto(gmailInboxClient.getMessage(account, email.getProviderMessageId())));
        }
        List<ParsedGmailMessage> messages = gmailInboxClient.getThread(account, email.getThreadId());
        return messages.stream().map(this::toThreadDto).toList();
    }

    @GetMapping("/{id}/replies")
    @Transactional(readOnly = true)
    public List<GeneratedReplyDto> replies(@PathVariable UUID id) {
        EmailMessage email = ownedEmailOrThrow(id);
        return replyService.historyFor(email.getId()).stream().map(emailMapper::toReplyDto).toList();
    }

    @PostMapping("/{id}/generate-reply")
    @Transactional
    public GeneratedReplyDto generateReply(@PathVariable UUID id, @Valid @RequestBody GenerateReplyRequest request) {
        User user = currentUserProvider.getCurrentUser();
        EmailMessage email = ownedEmailOrThrow(id);
        GeneratedReply reply = replyService.generateDraft(
                user.getId(), email, displayName(user), List.of(), request.instruction());
        return emailMapper.toReplyDto(reply);
    }

    @PostMapping("/{id}/replies/adjust")
    @Transactional
    public GeneratedReplyDto adjustReply(@PathVariable UUID id, @Valid @RequestBody AdjustReplyRequest request) {
        User user = currentUserProvider.getCurrentUser();
        EmailMessage email = ownedEmailOrThrow(id);

        AssistantCommandService.Classification classification = classify(request);
        GeneratedReply reply = replyService.adjustDraft(
                user.getId(), email, request.previousDraftId(), displayName(user),
                classification.adjustmentInstruction(), classification.toneLabel());
        return emailMapper.toReplyDto(reply);
    }

    /**
     * Sends the final, user-reviewed reply text via Gmail. The frontend is
     * required to only call this from an explicit "Send" click on a draft
     * the user has seen (and may have edited) in the composer — never
     * automatically after generation/adjustment.
     */
    @PostMapping("/{id}/send-reply")
    @Transactional
    public Map<String, String> sendReply(@PathVariable UUID id, @Valid @RequestBody SendReplyRequest request) {
        EmailMessage email = ownedEmailOrThrow(id);
        GeneratedReply draft = replyService.historyFor(email.getId()).stream()
                .filter(r -> r.getId().equals(request.draftId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found"));

        String gmailMessageId = replyService.sendReply(
                email.getMailAccount(), draft, request.finalBodyText(), request.toAddress(),
                request.subject(), null, null, email.getThreadId());
        return Map.of("gmailMessageId", gmailMessageId);
    }

    // --- helpers ---

    private MailAccount currentAccount() {
        User user = currentUserProvider.getCurrentUser();
        return mailAccountService.getActiveMailAccountOrThrow(user.getId());
    }

    private EmailMessage ownedEmailOrThrow(UUID emailId) {
        MailAccount account = currentAccount();
        EmailMessage email = emailRepository.findById(emailId)
                .orElseThrow(() -> new ResourceNotFoundException("Email not found"));
        if (!email.getMailAccount().getId().equals(account.getId())) {
            throw new ResourceNotFoundException("Email not found");
        }
        return email;
    }

    private AssistantCommandService.Classification classify(AdjustReplyRequest request) {
        if (request.button() != null && !request.button().isBlank()) {
            try {
                AssistantCommandService.Intent intent = AssistantCommandService.Intent.valueOf(request.button().trim().toUpperCase());
                return assistantCommandService.forButton(intent);
            } catch (IllegalArgumentException ignored) {
                // Falls through to free-text classification below.
            }
        }
        return assistantCommandService.classifyAdjustment(request.freeText());
    }

    private String displayName(User user) {
        return (user.getName() != null && !user.getName().isBlank()) ? user.getName() : user.getEmail();
    }

    private ThreadMessageDto toThreadDto(ParsedGmailMessage m) {
        return new ThreadMessageDto(m.messageId(), m.sender(), m.subject(), m.snippet(), m.bodyText(), m.receivedAt(), m.unread());
    }
}
