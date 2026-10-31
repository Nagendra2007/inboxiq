package com.mailroom.controller;

import com.mailroom.dto.ContactDto;
import com.mailroom.dto.AdjustReplyRequest;
import com.mailroom.dto.ComposeGenerateRequest;
import com.mailroom.dto.GeneratedReplyDto;
import com.mailroom.dto.SendReplyRequest;
import com.mailroom.dto.VoiceCommandRequest;
import com.mailroom.dto.VoiceCommandResultDto;
import com.mailroom.entity.GeneratedReply;
import com.mailroom.entity.MailAccount;
import com.mailroom.entity.User;
import com.mailroom.mapper.EmailMapper;
import com.mailroom.security.CurrentUserProvider;
import com.mailroom.service.AssistantCommandService;
import com.mailroom.service.MailAccountService;
import com.mailroom.service.VoiceComposeService;
import com.mailroom.service.ReplyService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

/**
 * Composing a brand-new email — not a reply to anything already synced.
 * Shares {@link ReplyService}'s AI plumbing and, critically, its "never
 * auto-send" guarantee with the reply composer: generate -> adjust (as many
 * times as the user likes) -> explicit review -> only THEN does /send ever
 * transmit anything, and only with whatever text the user last edited it to.
 */
@RestController
@RequestMapping("/api/compose")
public class ComposeController {

    private final CurrentUserProvider currentUserProvider;
    private final MailAccountService mailAccountService;
    private final ReplyService replyService;
    private final EmailMapper emailMapper;
    private final AssistantCommandService assistantCommandService;
    private final VoiceComposeService voiceComposeService;

    public ComposeController(CurrentUserProvider currentUserProvider,
                              MailAccountService mailAccountService,
                              ReplyService replyService,
                              EmailMapper emailMapper,
                              AssistantCommandService assistantCommandService,
                              VoiceComposeService voiceComposeService) {
        this.currentUserProvider = currentUserProvider;
        this.mailAccountService = mailAccountService;
        this.replyService = replyService;
        this.emailMapper = emailMapper;
        this.assistantCommandService = assistantCommandService;
        this.voiceComposeService = voiceComposeService;
    }

    /**
     * Splits one spoken instruction into the fields {@code /generate} takes.
     * Deliberately stops there: it answers with what it understood and never
     * drafts or sends, because the recipient came out of a transcript and a
     * transcript is least reliable on exactly the part that matters most.
     */
    @PostMapping("/voice-command")
    public VoiceCommandResultDto voiceCommand(@Valid @RequestBody VoiceCommandRequest request) {
        MailAccount account = currentAccount(currentUserProvider.getCurrentUser());
        VoiceComposeService.ParsedCommand parsed = voiceComposeService.parse(account.getId(), request.text());
        return new VoiceCommandResultDto(
                parsed.heardRecipient(),
                parsed.candidates().stream().map(c -> new ContactDto(c.name(), c.email())).toList(),
                parsed.subject(),
                parsed.instruction(),
                parsed.confident());
    }

    /** The address book, for the composer's recipient field to autocomplete from. */
    @GetMapping("/contacts")
    public List<ContactDto> contacts() {
        MailAccount account = currentAccount(currentUserProvider.getCurrentUser());
        return voiceComposeService.contacts(account.getId()).stream()
                .map(c -> new ContactDto(c.name(), c.email()))
                .toList();
    }

    @PostMapping("/generate")
    @Transactional
    public GeneratedReplyDto generate(@Valid @RequestBody ComposeGenerateRequest request) {
        User user = currentUserProvider.getCurrentUser();
        MailAccount account = currentAccount(user);
        GeneratedReply reply = replyService.generateComposeDraft(
                user.getId(), account, displayName(user), request.toAddress(), request.subject(), request.instruction());
        return emailMapper.toReplyDto(reply);
    }

    @PostMapping("/adjust")
    @Transactional
    public GeneratedReplyDto adjust(@Valid @RequestBody AdjustReplyRequest request) {
        User user = currentUserProvider.getCurrentUser();
        MailAccount account = currentAccount(user);

        AssistantCommandService.Classification classification = classify(request);
        GeneratedReply reply = replyService.adjustComposeDraft(
                user.getId(), account, request.previousDraftId(), displayName(user),
                classification.adjustmentInstruction(), classification.toneLabel());
        return emailMapper.toReplyDto(reply);
    }

    /**
     * Sends the final, user-reviewed draft via Gmail. Only ever called from
     * an explicit "Confirm & send" click on a draft the user has seen (and
     * may have edited) in the compose modal — never automatically after
     * generation or adjustment. There is no thread to reply into (this is a
     * brand-new email), so no In-Reply-To/References/threadId are set.
     */
    @PostMapping("/send")
    @Transactional
    public Map<String, String> send(@Valid @RequestBody SendReplyRequest request) {
        User user = currentUserProvider.getCurrentUser();
        MailAccount account = currentAccount(user);
        GeneratedReply draft = replyService.ownedDraftOrThrow(request.draftId(), account);

        String gmailMessageId = replyService.sendReply(
                account, draft, request.finalBodyText(), request.toAddress(), request.subject(), null, null, null);
        return Map.of("gmailMessageId", gmailMessageId);
    }

    private MailAccount currentAccount(User user) {
        return mailAccountService.getActiveMailAccountOrThrow(user.getId());
    }

    private String displayName(User user) {
        return (user.getName() != null && !user.getName().isBlank()) ? user.getName() : user.getEmail();
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
}
