package com.inboxiq.controller;

import com.inboxiq.dto.SyncStatusDto;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.User;
import com.inboxiq.exception.GmailIntegrationException;
import com.inboxiq.security.CurrentUserProvider;
import com.inboxiq.service.MailAccountService;
import com.inboxiq.service.RateLimiterService;
import com.inboxiq.service.SyncCoordinator;
import com.inboxiq.service.SyncTrigger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/gmail")
public class GmailController {

    private final CurrentUserProvider currentUserProvider;
    private final MailAccountService mailAccountService;
    private final SyncCoordinator syncCoordinator;
    private final RateLimiterService rateLimiterService;

    public GmailController(CurrentUserProvider currentUserProvider,
                           MailAccountService mailAccountService,
                           SyncCoordinator syncCoordinator,
                           RateLimiterService rateLimiterService) {
        this.currentUserProvider = currentUserProvider;
        this.mailAccountService = mailAccountService;
        this.syncCoordinator = syncCoordinator;
        this.rateLimiterService = rateLimiterService;
    }

    /**
     * The Sync button: starts an incremental pass in the background and
     * returns at once (202). Progress and results arrive over the realtime
     * stream (sync.started / email.saved / sync.completed). Rate-limited, as
     * the most Gmail-quota-hungry thing a user can trigger.
     */
    @PostMapping("/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> sync() {
        User user = currentUserProvider.getCurrentUser();
        MailAccount account = mailAccountService.getActiveMailAccountOrThrow(user.getId());
        if (account.isReauthRequired()) {
            throw GmailIntegrationException.reauthRequired(null);
        }
        rateLimiterService.checkGmailSyncRequest(user.getId());
        boolean started = syncCoordinator.requestSync(account.getId(), SyncTrigger.MANUAL);
        return Map.of("status", started ? "STARTED" : "ALREADY_RUNNING");
    }

    @GetMapping("/status")
    public SyncStatusDto status() {
        User user = currentUserProvider.getCurrentUser();
        MailAccount account = mailAccountService.getActiveMailAccountOrThrow(user.getId());
        return new SyncStatusDto(
                syncCoordinator.isSyncing(account.getId()),
                account.getInitialSyncCompletedAt() != null,
                account.getLastSyncAt(),
                account.getLastSyncError(),
                account.isReauthRequired());
    }
}
