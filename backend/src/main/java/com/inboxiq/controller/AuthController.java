package com.inboxiq.controller;

import com.inboxiq.dto.UserDto;
import com.inboxiq.entity.MailProvider;
import com.inboxiq.entity.User;
import com.inboxiq.repository.MailAccountRepository;
import com.inboxiq.security.AdminAccess;
import com.inboxiq.security.CurrentUserProvider;
import com.inboxiq.service.PrivacyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

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

    public AuthController(CurrentUserProvider currentUserProvider,
                           MailAccountRepository mailAccountRepository,
                           PrivacyService privacyService,
                           AdminAccess adminAccess) {
        this.currentUserProvider = currentUserProvider;
        this.mailAccountRepository = mailAccountRepository;
        this.privacyService = privacyService;
        this.adminAccess = adminAccess;
    }

    @GetMapping("/me")
    public UserDto me() {
        User user = currentUserProvider.getCurrentUser();
        boolean connected = mailAccountRepository.findByUserIdAndProviderAndActiveTrue(user.getId(), MailProvider.GOOGLE).isPresent();
        return new UserDto(user.getId(), user.getEmail(), user.getName(), connected, adminAccess.isAdmin(user));
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
