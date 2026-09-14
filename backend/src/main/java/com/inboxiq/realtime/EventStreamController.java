package com.inboxiq.realtime;

import com.inboxiq.entity.MailProvider;
import com.inboxiq.entity.User;
import com.inboxiq.repository.MailAccountRepository;
import com.inboxiq.security.CurrentUserProvider;
import com.inboxiq.service.SyncCoordinator;
import com.inboxiq.service.SyncTrigger;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The app's single realtime channel: one Server-Sent Events stream per open
 * tab (never one per email). It sits under /api/**, so it needs the same
 * signed-in session as every other API call, and it only ever carries the
 * signed-in user's own events.
 *
 * Opening it also means "the user is looking", so it starts an incremental
 * Gmail sync — this is how a returning user's inbox catches up on login.
 */
@RestController
@RequestMapping("/api/events")
public class EventStreamController {

    private final CurrentUserProvider currentUserProvider;
    private final EventStreamService eventStreamService;
    private final MailAccountRepository mailAccountRepository;
    private final SyncCoordinator syncCoordinator;

    public EventStreamController(CurrentUserProvider currentUserProvider,
                                 EventStreamService eventStreamService,
                                 MailAccountRepository mailAccountRepository,
                                 SyncCoordinator syncCoordinator) {
        this.currentUserProvider = currentUserProvider;
        this.eventStreamService = eventStreamService;
        this.mailAccountRepository = mailAccountRepository;
        this.syncCoordinator = syncCoordinator;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse response) {
        User user = currentUserProvider.getCurrentUser();
        // Ask any proxy in between not to buffer or transform the stream.
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = eventStreamService.open(user.getId());
        mailAccountRepository.findByUserIdAndProviderAndActiveTrue(user.getId(), MailProvider.GOOGLE)
                .filter(account -> !account.isReauthRequired())
                .ifPresent(account -> syncCoordinator.requestSync(account.getId(), SyncTrigger.CONNECT));
        return emitter;
    }
}
