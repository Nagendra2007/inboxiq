package com.inboxiq.service;

import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.MailProvider;
import com.inboxiq.entity.User;
import com.inboxiq.exception.ResourceNotFoundException;
import com.inboxiq.repository.MailAccountRepository;
import com.inboxiq.repository.UserRepository;
import com.inboxiq.security.TokenEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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

    @Transactional
    public void updateAccessToken(UUID mailAccountId, String newAccessToken, Instant newExpiry) {
        MailAccount account = mailAccountRepository.findById(mailAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("Mail account not found"));
        account.setEncryptedAccessToken(tokenEncryptionService.encrypt(newAccessToken));
        account.setTokenExpiry(newExpiry);
        mailAccountRepository.save(account);
    }

    @Transactional
    public void updateLastHistoryId(UUID mailAccountId, String historyId) {
        MailAccount account = mailAccountRepository.findById(mailAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("Mail account not found"));
        account.setLastHistoryId(historyId);
        mailAccountRepository.save(account);
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
        mailAccountRepository.save(account);
        log.info("Disconnected Gmail account id={}", account.getId());
    }
}
