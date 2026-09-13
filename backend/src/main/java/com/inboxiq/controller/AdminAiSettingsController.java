package com.inboxiq.controller;

import com.inboxiq.ai.AiClient;
import com.inboxiq.ai.AiConfig;
import com.inboxiq.dto.AiSettingsDto;
import com.inboxiq.dto.AiTestResultDto;
import com.inboxiq.dto.UpdateAiSettingsRequest;
import com.inboxiq.entity.User;
import com.inboxiq.exception.AiServiceException;
import com.inboxiq.security.AdminAccess;
import com.inboxiq.security.CurrentUserProvider;
import com.inboxiq.service.AiSettingsService;
import com.inboxiq.service.RateLimiterService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App-wide AI provider settings. Administrator only: every endpoint checks
 * {@link AdminAccess} and answers 403 to anyone else. Changes apply to all
 * users immediately.
 */
@RestController
@RequestMapping("/api/admin/ai-settings")
public class AdminAiSettingsController {

    private final CurrentUserProvider currentUserProvider;
    private final AdminAccess adminAccess;
    private final AiSettingsService aiSettingsService;
    private final AiClient aiClient;
    private final RateLimiterService rateLimiterService;

    public AdminAiSettingsController(CurrentUserProvider currentUserProvider,
                                     AdminAccess adminAccess,
                                     AiSettingsService aiSettingsService,
                                     AiClient aiClient,
                                     RateLimiterService rateLimiterService) {
        this.currentUserProvider = currentUserProvider;
        this.adminAccess = adminAccess;
        this.aiSettingsService = aiSettingsService;
        this.aiClient = aiClient;
        this.rateLimiterService = rateLimiterService;
    }

    @GetMapping
    public AiSettingsDto get() {
        requireAdmin();
        return aiSettingsService.view();
    }

    @PutMapping
    public AiSettingsDto update(@Valid @RequestBody UpdateAiSettingsRequest request) {
        User admin = requireAdmin();
        return aiSettingsService.save(request, admin.getEmail());
    }

    /** Back to the AI_* environment variables. */
    @DeleteMapping
    public AiSettingsDto reset() {
        requireAdmin();
        aiSettingsService.reset();
        return aiSettingsService.view();
    }

    /** Sends a one-word prompt with the given (unsaved) settings and reports what happened. */
    @PostMapping("/test")
    public AiTestResultDto test(@Valid @RequestBody UpdateAiSettingsRequest request) {
        User admin = requireAdmin();
        rateLimiterService.checkAiRequest(admin.getId());
        AiConfig candidate = aiSettingsService.candidate(request);

        long start = System.nanoTime();
        try {
            aiClient.complete(candidate, "You are a connectivity check. Reply with the single word OK.", "Reply with OK.", false);
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new AiTestResultDto(true,
                    candidate.provider().label() + " answered in " + ms + " ms using " + candidate.model() + ".", ms);
        } catch (AiServiceException e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String detail = e.getProviderDetail();
            return new AiTestResultDto(false, e.getMessage() + (detail != null ? " Provider said: " + detail : ""), ms);
        }
    }

    private User requireAdmin() {
        User user = currentUserProvider.getCurrentUser();
        adminAccess.requireAdmin(user);
        return user;
    }
}
