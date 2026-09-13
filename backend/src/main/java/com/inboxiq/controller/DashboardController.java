package com.inboxiq.controller;

import com.inboxiq.dto.DashboardDto;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.User;
import com.inboxiq.security.CurrentUserProvider;
import com.inboxiq.service.DashboardService;
import com.inboxiq.service.MailAccountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {

    private final CurrentUserProvider currentUserProvider;
    private final MailAccountService mailAccountService;
    private final DashboardService dashboardService;

    public DashboardController(CurrentUserProvider currentUserProvider,
                                MailAccountService mailAccountService,
                                DashboardService dashboardService) {
        this.currentUserProvider = currentUserProvider;
        this.mailAccountService = mailAccountService;
        this.dashboardService = dashboardService;
    }

    @GetMapping("/api/dashboard")
    public DashboardDto dashboard() {
        User user = currentUserProvider.getCurrentUser();
        MailAccount account = mailAccountService.getActiveMailAccountOrThrow(user.getId());
        DashboardService.DashboardStats stats = dashboardService.statsFor(account.getId());
        return new DashboardDto(
                stats.totalEmails(), stats.unreadEmails(), stats.receivedLast7Days(),
                stats.highPriority(), stats.mediumPriority(), stats.lowPriority(),
                stats.highRisk(), stats.mediumRisk(), stats.awaitingReply(), stats.openActionItems()
        );
    }
}
