package com.inboxiq.security;

import com.inboxiq.entity.User;
import com.inboxiq.exception.UnauthorizedException;
import com.inboxiq.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Resolves the InboxIQ {@link User} behind the current HTTP session's
 * Spring Security authentication, so controllers/services never touch
 * {@link SecurityContextHolder} or OAuth2 principal attributes directly.
 */
@Component
public class CurrentUserProvider {

    private final UserRepository userRepository;

    public CurrentUserProvider(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OAuth2User oAuth2User)) {
            throw new UnauthorizedException("Sign in with Google to continue.");
        }
        String email = oAuth2User.getAttribute("email");
        if (email == null) {
            throw new UnauthorizedException("Google did not provide an email address for this account.");
        }
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("No InboxIQ account found for this session."));
    }
}
