package com.inboxiq.controller;

import com.inboxiq.dto.UserDto;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.MailProvider;
import com.inboxiq.entity.User;
import com.inboxiq.repository.MailAccountRepository;
import com.inboxiq.security.AdminAccess;
import com.inboxiq.security.CurrentUserProvider;
import com.inboxiq.service.PrivacyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import java.util.Optional;

/**
 * Session/account endpoints. Login itself is handled entirely by Spring
 * Security's OAuth2 login flow (see SecurityConfig) — there is no
 * {@code POST /api/auth/login}, since the browser is redirected straight to
 * Google. {@code /api/auth/logout} is likewise wired at the security-filter
 * level (SecurityConfig#logout), not here.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CurrentUserProvider currentUserProvider;
    private final MailAccountRepository mailAccountRepository;
    private final PrivacyService privacyService;
    private final AdminAccess adminAccess;
    private final CookieSerializer cookieSerializer;

    public AuthController(CurrentUserProvider currentUserProvider,
                           MailAccountRepository mailAccountRepository,
                           PrivacyService privacyService,
                           AdminAccess adminAccess,
                           CookieSerializer cookieSerializer) {
        this.currentUserProvider = currentUserProvider;
        this.mailAccountRepository = mailAccountRepository;
        this.privacyService = privacyService;
        this.adminAccess = adminAccess;
        this.cookieSerializer = cookieSerializer;
    }

    /**
     * Who is signed in. Called on every app load, so it also re-issues the
     * session cookie with a fresh expiry: sign-in lasts as long as the app is
     * used at least once per session lifetime (30 days by default).
     */
    @GetMapping("/me")
    public UserDto me(HttpServletRequest request, HttpServletResponse response) {
        User user = currentUserProvider.getCurrentUser();
        Optional<MailAccount> account = mailAccountRepository.findByUserIdAndProviderAndActiveTrue(user.getId(), MailProvider.GOOGLE);

        HttpSession session = request.getSession(false);
        if (session != null) {
            cookieSerializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, session.getId()));
        }
        return new UserDto(user.getId(), user.getEmail(), user.getName(),
                account.isPresent(),
                account.map(MailAccount::isReauthRequired).orElse(false),
                adminAccess.isAdmin(user));
    }

    /** Revokes/clears stored Gmail tokens; previously synced data is kept. */
    @PostMapping("/disconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect() {
        User user = currentUserProvider.getCurrentUser();
        privacyService.disconnectGmail(user.getId());
    }

    /**
     * Full, irreversible data deletion — every synced email, its analysis,
     * action items, and reply drafts, plus the disconnected mail account
     * itself. Requires the caller to already be authenticated as the user
     * whose data this is; there is no separate "confirm" step at the API
     * level because the frontend is required to show its own confirmation
     * dialog before ever issuing this request (see README "Privacy").
     */
    @DeleteMapping("/data")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAllData() {
        User user = currentUserProvider.getCurrentUser();
        privacyService.deleteAllUserData(user.getId());
    }
}
