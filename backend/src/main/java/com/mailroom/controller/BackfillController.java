package com.mailroom.controller;

import com.mailroom.entity.MailAccount;
import com.mailroom.security.CurrentUserProvider;
import com.mailroom.service.BackfillService;
import com.mailroom.service.MailAccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Importing older mail, on request rather than automatically.
 *
 * <p>Automatic would be worse: it is thousands of Gmail calls against a
 * shared per-user quota, and it is only worth spending when somebody
 * actually wants to search their history.
 */
@RestController
@RequestMapping("/api/mail/backfill")
public class BackfillController {

    /** Longer than this and it is simpler to say "everything". */
    private static final int MAX_MONTHS = 60;

    private final CurrentUserProvider currentUserProvider;
    private final MailAccountService mailAccountService;
    private final BackfillService backfill;

    public BackfillController(CurrentUserProvider currentUserProvider,
                              MailAccountService mailAccountService,
                              BackfillService backfill) {
        this.currentUserProvider = currentUserProvider;
        this.mailAccountService = mailAccountService;
        this.backfill = backfill;
    }

    public record StartRequest(Integer months) {}

    @GetMapping
    public BackfillService.Progress progress() {
        return backfill.progress(account().getId());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> start(@RequestBody(required = false) StartRequest request) {
        int months = request == null || request.months() == null ? 12 : request.months();
        if (months < 0 || months > MAX_MONTHS) months = 12;

        boolean started = backfill.start(account().getId(), months);
        // Not an error: asking twice while one is running is what a second
        // tab, or an impatient second click, looks like.
        return ResponseEntity.ok(Map.of("started", started, "months", months));
    }

    private MailAccount account() {
        return mailAccountService.getActiveMailAccountOrThrow(currentUserProvider.getCurrentUser().getId());
    }
}
