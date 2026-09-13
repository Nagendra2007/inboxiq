package com.inboxiq.dto;

public record DashboardDto(
        long totalEmails,
        long unreadEmails,
        long receivedLast7Days,
        long highPriority,
        long mediumPriority,
        long lowPriority,
        long highRisk,
        long mediumRisk,
        long awaitingReply,
        long openActionItems
) {}
