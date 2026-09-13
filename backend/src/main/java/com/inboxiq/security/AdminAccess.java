package com.inboxiq.security;

import com.inboxiq.config.AppProperties;
import com.inboxiq.dto.AdministratorsDto;
import com.inboxiq.entity.User;
import com.inboxiq.exception.ApiException;
import com.inboxiq.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may change app-wide settings. The ADMIN_EMAILS environment variable
 * (comma-separated) decides; when it's empty, the first account ever created
 * on the deployment — in practice whoever deployed it and signed in first —
 * is the administrator. Sign-in is limited to the Google OAuth app's test
 * users while it's in Testing mode, so that bootstrap can't be raced by a
 * stranger; set ADMIN_EMAILS to pin it explicitly.
 */
@Component
public class AdminAccess {

    private final AppProperties appProperties;
    private final UserRepository userRepository;

    public AdminAccess(AppProperties appProperties, UserRepository userRepository) {
        this.appProperties = appProperties;
        this.userRepository = userRepository;
    }

    public boolean isAdmin(User user) {
        Set<String> admins = configuredAdmins();
        if (!admins.isEmpty()) {
            return user.getEmail() != null && admins.contains(user.getEmail().toLowerCase(Locale.ROOT));
        }
        return userRepository.findFirstByOrderByCreatedAtAscIdAsc()
                .map(first -> first.getId().equals(user.getId()))
                .orElse(false);
    }

    /** Everyone who is an administrator right now, and which rule made them one. */
    public AdministratorsDto describe() {
        Set<String> admins = configuredAdmins();
        if (!admins.isEmpty()) {
            return new AdministratorsDto("ADMIN_EMAILS", admins.stream()
                    .map(email -> new AdministratorsDto.Admin(email, userRepository.existsByEmailIgnoreCase(email)))
                    .toList());
        }
        return new AdministratorsDto("FIRST_ACCOUNT", userRepository.findFirstByOrderByCreatedAtAscIdAsc()
                .map(first -> List.of(new AdministratorsDto.Admin(first.getEmail(), true)))
                .orElse(List.of()));
    }

    private Set<String> configuredAdmins() {
        return Arrays.stream(appProperties.getAdminEmails().split(","))
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .filter(email -> !email.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public void requireAdmin(User user) {
        if (!isAdmin(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only the administrator can change the AI provider.");
        }
    }
}
