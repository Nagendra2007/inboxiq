package com.inboxiq.repository;

/** The email-side dashboard totals, counted in one pass instead of three. */
public record EmailCounts(long total, long unread, long receivedSince) {}
