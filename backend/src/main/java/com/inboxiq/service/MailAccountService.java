package com.inboxiq.service;

import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.MailProvider;
import com.inboxiq.entity.User;
import com.inboxiq.exception.GmailIntegrationException;
import com.inboxiq.exception.ResourceNotFoundException;
import com.inboxiq.repository.MailAccountRepository;
import com.inboxiq.repository.UserRepository;
import com.inboxiq.security.TokenEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Owns the {@link User} <-> {@link MailAccount} lifecycle: first-login
 * upsert, reading/writing OAuth tokens (always through
 * {@link TokenEncryptionService}, never storing plaintext), and disconnect.
 *
 * Nothing in this class ever logs a token value, only account ids/emails.
 */
@Service
public class MailAccountService {

    private static final Logger log = LoggerFactory.getLogger(MailAccountService.class);

    private final UserRepository userRepository;
    private final MailAccountRepository mailAccountRepository;
    private final TokenEncryptionService tokenEncryptionService;

    public MailAccountService(UserRepository userRepository,
                               MailAccountRepository mailAccountRepository,
                               TokenEncryptionService tokenEncryptionService) {
        this.userRepository = userRepository;
        this.mailAccountRepository = mailAccountRepository;
        this.tokenEncryptionService = tokenEncryptionService;
    }

    /**
     * Called once per successful Google OAuth login. Creates the user/mail
     * account on first login, or updates the stored tokens on subsequent
     * logins. Google only returns a refresh token when the consent screen
     * was actually shown (we force this with {@code prompt=consent}), so
     * {@code refreshToken} may be null on a re-login — in that case the
     * previously stored refresh token (if any) is left untouched.
     */
    @Transactional
    public MailAccount upsertFromOAuthLogin(String email, String name,
                                             String accessToken, String refreshToken,
                                             Instant accessTokenExpiry) {
        User user = userRepository.findByEmail(email).orElseGet(() -> {
            User created = new User(email, name);
            log.info("Creating new InboxIQ user");
            return userRepository.save(created);
        });
        if (name != null && !name.equals(user.getName())) {
            user.setName(name);
            userRepository.save(user);
        }

        MailAccount account = mailAccountRepository.findByUserIdAndProvider(user.getId(), MailProvider.GOOGLE)
                .orElseGet(() -> {
                    MailAccount created = new MailAccount();
                    created.setUser(user);
                    created.setProvider(MailProvider.GOOGLE);
                    return created;
                });

        account.setProviderEmail(email);
        account.setActive(true);
        account.setEncryptedAccessToken(tokenEncryptionService.encrypt(accessToken));
        if (refreshToken != null && !refreshToken.isBlank()) {
            account.setEncryptedRefreshToken(tokenEncryptionService.encrypt(refreshToken));
            // A fresh grant from Google: Gmail access works again.
            account.setReauthRequired(false);
        }
        account.setTokenExpiry(accessTokenExpiry);

        MailAccount saved = mailAccountRepository.save(account);
        log.info("Linked Gmail account id={} for user", saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public MailAccount getActiveMailAccountOrThrow(UUID userId) {
        return mailAccountRepository.findByUserIdAndProviderAndActiveTrue(userId, MailProvider.GOOGLE)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No connected Gmail account. Please connect Gmail first."));
    }

    /** Returns the decrypted current access token — callers must not persist or log it. */
    public String decryptAccessToken(MailAccount account) {
        return tokenEncryptionService.decrypt(account.getEncryptedAccessToken());
    }

    /** Returns the decrypted refresh token, or null if none is stored. */
    public String decryptRefreshToken(MailAccount account) {
        return tokenEncryptionService.decrypt(account.getEncryptedRefreshToken());
    }

    /**
     * True when InboxIQ holds a Gmail grant it can keep using without the
     * user: a refresh token Google hasn't rejected.
     */
    public boolean hasUsableGmailGrant(MailAccount account) {
        return account.getEncryptedRefreshToken() != null && !account.isReauthRequired();
    }

    // The writes below run in their own transaction (REQUIRES_NEW): they are
    // made from background sync threads and from inside read-only request
    // transactions, where a joined write would be silently dropped, or rolled
    // back along with the request that hit the error they record.

    /**
     * Stores a refreshed access token and also updates {@code account} itself,
     * so the caller's copy stays valid for the rest of its work.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateAccessToken(MailAccount account, String newAccessToken, Instant newExpiry) {
        String encrypted = tokenEncryptionService.encrypt(newAccessToken);
        mailAccountRepository.findById(account.getId()).ifPresent(stored -> {
            stored.setEncryptedAccessToken(encrypted);
            stored.setTokenExpiry(newExpiry);
            mailAccountRepository.save(stored);
        });
        account.setEncryptedAccessToken(encrypted);
        account.setTokenExpiry(newExpiry);
    }

    /** Forces the next Gmail call to refresh the access token first (after Gmail answered 401). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireAccessToken(UUID mailAccountId) {
        mailAccountRepository.findById(mailAccountId).ifPresent(account -> {
            account.setTokenExpiry(null);
            mailAccountRepository.save(account);
        });
    }

    /** Google rejected the stored grant: keep the account, ask the user to reconnect Gmail. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReauthRequired(UUID mailAccountId) {
        mailAccountRepository.findById(mailAccountId).ifPresent(account -> {
            if (account.isReauthRequired()) return;
            account.setReauthRequired(true);
            account.setLastSyncError(GmailIntegrationException.reauthRequired(null).getMessage());
            mailAccountRepository.save(account);
            log.info("Gmail account id={} needs to be reconnected", mailAccountId);
        });
    }

    /**
     * Records a completed sync pass. {@code historyId} becomes the checkpoint
     * the next pass continues from; it is only ever written here, after every
     * change up to it has been applied.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSyncSuccess(UUID mailAccountId, String historyId, boolean initialSyncCompleted) {
        mailAccountRepository.findById(mailAccountId).ifPresent(account -> {
            if (historyId != null) account.setLastHistoryId(historyId);
            Instant now = Instant.now();
            if (initialSyncCompleted && account.getInitialSyncCompletedAt() == null) {
                account.setInitialSyncCompletedAt(now);
            }
            account.setLastSyncAt(now);
            account.setLastSyncError(null);
            mailAccountRepository.save(account);
        });
    }

    /** Records a failed pass. The checkpoint is left alone, so the next pass redoes the same changes. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSyncFailure(UUID mailAccountId, String userSafeMessage) {
        mailAccountRepository.findById(mailAccountId).ifPresent(account -> {
            account.setLastSyncError(userSafeMessage == null ? null
                    : userSafeMessage.substring(0, Math.min(500, userSafeMessage.length())));
            mailAccountRepository.save(account);
        });
    }

    /**
     * Disconnects Gmail: clears the stored (encrypted) tokens and marks the
     * account inactive. Does NOT delete previously synced emails/analysis —
     * see {@code PrivacyService#deleteAllUserData} for a full data wipe.
     */
    @Transactional
    public void disconnect(UUID userId) {
        MailAccount account = mailAccountRepository.findByUserIdAndProvider(userId, MailProvider.GOOGLE)
                .orElse(null);
        if (account == null) return;
        account.setActive(false);
        account.setEncryptedAccessToken(null);
        account.setEncryptedRefreshToken(null);
        account.setTokenExpiry(null);
        account.setReauthRequired(false);
        mailAccountRepository.save(account);
        log.info("Disconnected Gmail account id={}", account.getId());
    }
}
