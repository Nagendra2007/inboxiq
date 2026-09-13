package com.inboxiq.security;

import com.inboxiq.config.AppProperties;
import com.inboxiq.entity.User;
import com.inboxiq.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAccessTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AppProperties properties = new AppProperties();
    private final AdminAccess adminAccess = new AdminAccess(properties, userRepository);

    private static User user(String email) {
        User user = new User(email, email);
        user.setId(UUID.randomUUID());
        return user;
    }

    @Test
    void adminEmailsDecideWhenSet() {
        properties.setAdminEmails(" Owner@Example.com , second@example.com ");

        assertThat(adminAccess.isAdmin(user("owner@example.com"))).isTrue();
        assertThat(adminAccess.isAdmin(user("second@example.com"))).isTrue();
        assertThat(adminAccess.isAdmin(user("someone@example.com"))).isFalse();
    }

    @Test
    void withoutAdminEmailsTheFirstAccountIsTheAdministrator() {
        User first = user("first@example.com");
        when(userRepository.findFirstByOrderByCreatedAtAscIdAsc()).thenReturn(Optional.of(first));

        assertThat(adminAccess.isAdmin(first)).isTrue();
        assertThat(adminAccess.isAdmin(user("later@example.com"))).isFalse();
    }
}
