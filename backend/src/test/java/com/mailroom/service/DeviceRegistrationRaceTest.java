package com.mailroom.service;

import com.mailroom.ai.FcmClient;
import com.mailroom.entity.DeviceToken;
import com.mailroom.entity.User;
import com.mailroom.repository.DeviceTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Losing the race to insert a device token.
 *
 * <p>The database enforces one row per token, and the app offers the same
 * token from two places on launch. The integration test cannot show this —
 * H2 serialises the two writes and neither ever loses — so the collision
 * is produced directly here, by a repository that refuses the first insert
 * exactly as Postgres does.
 */
class DeviceRegistrationRaceTest {

    private static final String TOKEN = "fK9mQ2vXTnO3pL8wRcY1dZ:APA91bHqW3nRxT7kVm2Zp0LsDgYbQj8";

    @Test
    void theLoserUpdatesTheRowTheWinnerWrote() {
        DeviceTokenRepository tokens = mock(DeviceTokenRepository.class);
        DeviceToken winnersRow = new DeviceToken();

        // First look: nothing there yet, so this call tries to insert and
        // the unique index rejects it. Second look: the winner's row.
        when(tokens.findByToken(TOKEN))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnersRow));
        when(tokens.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value"))
                .thenAnswer(call -> call.getArgument(0));

        User user = new User("race@example.com", "Race");
        DeviceNotificationService service =
                new DeviceNotificationService(tokens, mock(FcmClient.class), transactions());

        assertThatCode(() -> service.register(user, TOKEN, "android")).doesNotThrowAnyException();

        verify(tokens, times(2)).saveAndFlush(any());
        // The phone ends up registered to the right account either way,
        // which is the whole point of not letting the collision surface.
        assertThat(winnersRow.getUser()).isSameAs(user);
        assertThat(winnersRow.getToken()).isEqualTo(TOKEN);
    }

    /** Enough of a transaction manager for TransactionTemplate to run on. */
    private static PlatformTransactionManager transactions() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return manager;
    }
}
