package com.mailroom.entity;

public enum AnalysisStatus {
    /** Queued, or waiting to be queued, for the AI. */
    PENDING,
    COMPLETED,
    FAILED,
    /**
     * Stored without being queued, and analysed only when something needs
     * it — someone opening the email, or a search matching it.
     *
     * <p>Importing a mailbox's history would otherwise mean one AI call per
     * message for thousands of messages, nearly all of which nobody will
     * ever read. The difference from {@link #PENDING} is intent, and it
     * matters: the resume job treats PENDING as unfinished work and would
     * eventually analyse the lot.
     */
    DEFERRED
}
