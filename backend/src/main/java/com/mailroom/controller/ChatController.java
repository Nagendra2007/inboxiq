package com.mailroom.controller;

import com.mailroom.entity.ChatConversation;
import com.mailroom.entity.ChatMessage;
import com.mailroom.entity.User;
import com.mailroom.security.CurrentUserProvider;
import com.mailroom.service.ChatService;
import com.mailroom.service.MailAccountService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asking questions about your own mail.
 *
 * <p>Every route resolves the mailbox from the session, never from the
 * request: a conversation id in a URL is not permission to read it.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final CurrentUserProvider currentUserProvider;
    private final MailAccountService mailAccountService;
    private final ChatService chat;

    public ChatController(CurrentUserProvider currentUserProvider,
                          MailAccountService mailAccountService,
                          ChatService chat) {
        this.currentUserProvider = currentUserProvider;
        this.mailAccountService = mailAccountService;
        this.chat = chat;
    }

    public record AskRequest(
            UUID conversationId,
            @NotBlank @Size(max = 500) String question) {}

    public record ConversationSummary(UUID id, String title, Instant updatedAt) {}

    public record Turn(UUID id, String role, String content, Instant at, List<ChatService.Citation> citations) {}

    @PostMapping("/ask")
    public ChatService.Answer ask(@Valid @RequestBody AskRequest request) {
        User user = currentUserProvider.getCurrentUser();
        UUID mailAccountId = mailAccountService.getActiveMailAccountOrThrow(user.getId()).getId();
        return chat.ask(user, mailAccountId, request.conversationId(), request.question());
    }

    @GetMapping("/conversations")
    public List<ConversationSummary> conversations() {
        return chat.list(currentUserProvider.getCurrentUser().getId()).stream()
                .map(c -> new ConversationSummary(c.getId(), c.getTitle(), c.getUpdatedAt()))
                .toList();
    }

    @GetMapping("/conversations/{id}")
    public List<Turn> history(@PathVariable UUID id) {
        User user = currentUserProvider.getCurrentUser();
        List<ChatMessage> turns = chat.history(user.getId(), id);
        return turns.stream()
                .map(message -> new Turn(
                        message.getId(),
                        message.getRole().name().toLowerCase(),
                        message.getContent(),
                        message.getCreatedAt(),
                        chat.citationsOf(message)))
                .toList();
    }

    @DeleteMapping("/conversations/{id}")
    public Map<String, Boolean> delete(@PathVariable UUID id) {
        chat.delete(currentUserProvider.getCurrentUser().getId(), id);
        return Map.of("deleted", true);
    }
}
