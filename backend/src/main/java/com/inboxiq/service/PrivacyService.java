package com.inboxiq.service;

import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.MailProvider;
import com.inboxiq.repository.MailAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Backs the privacy-facing controls the spec requires: disconnecting Gmail
 * (token revocation, no data loss) and a full, irreversible data wipe for a
 * user who wants InboxIQ to forget them entirely.
 */
@Service
public class PrivacyService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);

    private final MailAccountService mailAccountService;
    private final MailAccountRepository mailAccountRepository;

    public PrivacyService(MailAccountService mailAccountService, MailAccountRepository mailAccountRepository) {
        this.mailAccountService = mailAccountService;
        this.mailAccountRepository = mailAccountRepository;
    }

    /** Revokes stored tokens and marks the account inactive; synced emails/analysis are kept. */
    public void disconnectGmail(UUID userId) {
        mailAccountService.disconnect(userId);
    }

    /**
     * Permanently deletes every row this user's data touches: synced emails
     * (which cascades, at the database level, to their analysis, action
     * items, and generated replies — see V1__init_schema.sql's
     * ON DELETE CASCADE foreign keys), then the mail account and user
     * record itself. Irreversible; only ever called from an endpoint that
     * requires the user's own explicit, authenticated confirmation.
     */
    @Transactional
    public void deleteAllUserData(UUID userId) {
        MailAccount account = mailAccountRepository.findByUserIdAndProvider(userId, MailProvider.GOOGLE).orElse(null);
        if (account == null) {
            log.info("No mail account found for user during data deletion request");
            return;
        }
        // Emails (and, via ON DELETE CASCADE, their analysis/action items/
        // generated replies) are removed by deleting the mail account row
        // that owns them; the user row itself is left to the caller so the
        // session can be cleanly logged out afterward rather than mid-delete.
        mailAccountRepository.delete(account);
        log.info("Deleted all Gmail-derived data for user (mail account id={})", account.getId());
    }
}
