package com.inboxiq.service;

/** Why a Gmail sync pass was started (sent with sync.* events; only MANUAL is rate-limited). */
public enum SyncTrigger {
    /** Right after Google sign-in. */
    LOGIN,
    /** A browser tab opened its realtime stream (the app was opened or came back). */
    CONNECT,
    /** Periodic check while the user has InboxIQ open. */
    POLL,
    /** Periodic check while the user is away, so mail keeps arriving with the app closed. */
    BACKGROUND,
    /** The Sync button. */
    MANUAL
}
