package com.inboxiq.controller;

import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.User;
import com.inboxiq.security.CurrentUserProvider;
import com.inboxiq.service.EmailSyncService;
import com.inboxiq.service.MailAccountService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gmail")
public class GmailController {

    private final CurrentUserProvider currentUserProvider;
    private final MailAccountService mailAccountService;
    private final EmailSyncService emailSyncService;

    public GmailController(CurrentUserProvider currentUserProvider,
                            MailAccountService mailAccountService,
                            EmailSyncService emailSyncService) {
        this.currentUserProvider = currentUserProvider;
        this.mailAccountService = mailAccountService;
        this.emailSyncService = emailSyncService;
    }

    /**
     * Triggers a sync pass against the user's connected Gmail account.
     * Rate-limited (see RateLimiterService) since this is the most
     * Gmail-API-quota-expensive endpoint in the app.
     */
    @PostMapping("/sync")
    @Transactional
    public EmailSyncService.SyncResult sync() {
        User user = currentUserProvider.getCurrentUser();
        MailAccount account = mailAccountService.getActiveMailAccountOrThrow(user.getId());
        return emailSyncService.syncInbox(account);
    }
}
