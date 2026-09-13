package com.inboxiq.service;

import com.inboxiq.ai.AiClient;
import com.inboxiq.ai.AiResponseParser;
import com.inboxiq.ai.dto.ReplyAiResult;
import com.inboxiq.ai.prompt.EmailReplyPrompt;
import com.inboxiq.ai.prompt.PromptPair;
import com.inboxiq.entity.EmailMessage;
import com.inboxiq.entity.GeneratedReply;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.exception.ResourceNotFoundException;
import com.inboxiq.gmail.GmailInboxClient;
import com.inboxiq.gmail.OutgoingReply;
import com.inboxiq.repository.GeneratedReplyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Generates, adjusts, and (only on the user's explicit confirmation) sends
 * AI-drafted replies. Nothing in this class ever calls
 * {@link GmailInboxClient#sendReply} except {@link #sendReply}, and that
 * method only ever runs from the dedicated "send" endpoint the user
 * triggers by clicking Send in the composer — never automatically after
 * generation. See README "AI reply workflow: draft, review, edit, confirm,
 * send — never auto-send."
 */
@Service
public class ReplyService {

    private final AiClient aiClient;
    private final AiResponseParser aiResponseParser;
    private final AiSettingsService aiSettingsService;
    private final GeneratedReplyRepository generatedReplyRepository;
    private final GmailInboxClient gmailInboxClient;
    private final RateLimiterService rateLimiterService;

    public ReplyService(AiClient aiClient,
                         AiResponseParser aiResponseParser,
                         AiSettingsService aiSettingsService,
                         GeneratedReplyRepository generatedReplyRepository,
                         GmailInboxClient gmailInboxClient,
                         RateLimiterService rateLimiterService) {
        this.aiClient = aiClient;
        this.aiResponseParser = aiResponseParser;
        this.aiSettingsService = aiSettingsService;
        this.generatedReplyRepository = generatedReplyRepository;
        this.gmailInboxClient = gmailInboxClient;
        this.rateLimiterService = rateLimiterService;
    }

    /**
     * Generates a brand-new draft from the user's one-line natural-language
     * instruction. {@code userId} is supplied by the caller (the
     * authenticated request, in the controller layer) rather than navigated
     * from {@code email.getMailAccount().getUser()}, since this method may
     * run outside the transaction that loaded {@code email} and that
     * association is lazy.
     */
    @Transactional
    public GeneratedReply generateDraft(UUID userId, EmailMessage email, String userDisplayName,
                                         List<String> priorThreadMessages, String userInstruction) {
        rateLimiterService.checkAiRequest(userId);
        PromptPair prompt = EmailReplyPrompt.buildGenerate(
                userDisplayName, email.getSender(), email.getSubject(), email.getBodyText(),
                priorThreadMessages, userInstruction);

        String raw = aiClient.complete(aiSettingsService.current(), prompt.systemPrompt(), prompt.userPrompt(), true);
        ReplyAiResult result = aiResponseParser.parseReply(raw);

        GeneratedReply reply = new GeneratedReply();
        reply.setMailAccount(email.getMailAccount());
        reply.setEmail(email);
        reply.setUserPrompt(userInstruction);
        reply.setGeneratedContent(result.reply());
        return generatedReplyRepository.save(reply);
    }

    /**
     * Re-writes an existing draft per a quick-adjustment button (shorter /
     * formal / friendly / regenerate) or a free-form follow-up instruction.
     * Always creates a new {@link GeneratedReply} row rather than mutating
     * the old one, preserving the full history of drafts for the email.
     */
    @Transactional
    public GeneratedReply adjustDraft(UUID userId, EmailMessage email, UUID previousDraftId,
                                       String userDisplayName, String adjustmentInstruction, String toneLabel) {
        rateLimiterService.checkAiRequest(userId);
        GeneratedReply previous = generatedReplyRepository.findById(previousDraftId)
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found"));

        PromptPair prompt = EmailReplyPrompt.buildAdjust(userDisplayName, previous.getGeneratedContent(), adjustmentInstruction);
        String raw = aiClient.complete(aiSettingsService.current(), prompt.systemPrompt(), prompt.userPrompt(), true);
        ReplyAiResult result = aiResponseParser.parseReply(raw);

        GeneratedReply reply = new GeneratedReply();
        reply.setMailAccount(email.getMailAccount());
        reply.setEmail(email);
        reply.setUserPrompt(previous.getUserPrompt());
        reply.setGeneratedContent(result.reply());
        reply.setToneAdjustment(toneLabel);
        return generatedReplyRepository.save(reply);
    }

    public List<GeneratedReply> historyFor(UUID emailId) {
        return generatedReplyRepository.findByEmailIdOrderByCreatedAtDesc(emailId);
    }

    /**
     * Generates a brand-new draft with no source email — a from-scratch
     * compose, not a reply. Shares the same AI plumbing (and the same
     * "never auto-send" guarantee) as {@link #generateDraft}; only the
     * prompt differs (see {@link EmailReplyPrompt#buildCompose}).
     */
    @Transactional
    public GeneratedReply generateComposeDraft(UUID userId, MailAccount account, String userDisplayName,
                                                String toAddress, String subject, String userInstruction) {
        rateLimiterService.checkAiRequest(userId);
        PromptPair prompt = EmailReplyPrompt.buildCompose(userDisplayName, toAddress, subject, userInstruction);
        String raw = aiClient.complete(aiSettingsService.current(), prompt.systemPrompt(), prompt.userPrompt(), true);
        ReplyAiResult result = aiResponseParser.parseReply(raw);

        GeneratedReply reply = new GeneratedReply();
        reply.setMailAccount(account);
        reply.setToAddress(toAddress);
        reply.setDraftSubject(subject);
        reply.setUserPrompt(userInstruction);
        reply.setGeneratedContent(result.reply());
        return generatedReplyRepository.save(reply);
    }

    /** Same quick-adjustment flow as {@link #adjustDraft}, for a compose draft instead of a reply. */
    @Transactional
    public GeneratedReply adjustComposeDraft(UUID userId, MailAccount account, UUID previousDraftId,
                                              String userDisplayName, String adjustmentInstruction, String toneLabel) {
        rateLimiterService.checkAiRequest(userId);
        GeneratedReply previous = ownedDraftOrThrow(previousDraftId, account);

        PromptPair prompt = EmailReplyPrompt.buildAdjust(userDisplayName, previous.getGeneratedContent(), adjustmentInstruction);
        String raw = aiClient.complete(aiSettingsService.current(), prompt.systemPrompt(), prompt.userPrompt(), true);
        ReplyAiResult result = aiResponseParser.parseReply(raw);

        GeneratedReply reply = new GeneratedReply();
        reply.setMailAccount(account);
        reply.setToAddress(previous.getToAddress());
        reply.setDraftSubject(previous.getDraftSubject());
        reply.setUserPrompt(previous.getUserPrompt());
        reply.setGeneratedContent(result.reply());
        reply.setToneAdjustment(toneLabel);
        return generatedReplyRepository.save(reply);
    }

    /**
     * Looks up a draft and verifies it belongs to this account — the
     * compose-flow equivalent of {@code EmailController#ownedEmailOrThrow},
     * needed because a compose draft has no source email to check ownership
     * through. {@link ResourceNotFoundException} either way (not found, or
     * owned by someone else) so the two cases are indistinguishable.
     */
    public GeneratedReply ownedDraftOrThrow(UUID draftId, MailAccount account) {
        GeneratedReply draft = generatedReplyRepository.findById(draftId)
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found"));
        if (!draft.getMailAccount().getId().equals(account.getId())) {
            throw new ResourceNotFoundException("Draft not found");
        }
        return draft;
    }

    /**
     * Sends the (possibly user-edited) final text via Gmail. This is the
     * ONLY path that ever transmits an email on the user's behalf, and it
     * is only ever invoked from the explicit "Send" action after the user
     * has reviewed/edited the draft in the composer — see the controller
     * layer, which requires the request to come from an authenticated,
     * confirmed send action, never an automated one.
     */
    @Transactional
    public String sendReply(MailAccount account, GeneratedReply draft, String finalBodyText,
                             String toAddress, String subject, String inReplyToMessageId,
                             String references, String gmailThreadId) {
        OutgoingReply outgoing = new OutgoingReply(toAddress, subject, finalBodyText,
                inReplyToMessageId, references, gmailThreadId);
        String gmailMessageId = gmailInboxClient.sendReply(account, outgoing);

        draft.setGeneratedContent(finalBodyText);
        draft.setSent(true);
        draft.setSentAt(java.time.Instant.now());
        draft.setGmailMessageId(gmailMessageId);
        generatedReplyRepository.save(draft);
        return gmailMessageId;
    }
}
