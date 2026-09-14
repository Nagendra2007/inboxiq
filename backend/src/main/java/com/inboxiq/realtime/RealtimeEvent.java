package com.inboxiq.realtime;

/**
 * Names of the events sent over {@code GET /api/events}. The frontend
 * listens for exactly these (see frontend/src/context/RealtimeContext.tsx).
 */
public final class RealtimeEvent {

    private RealtimeEvent() {}

    /** First event on every stream. */
    public static final String CONNECTED = "connected";

    public static final String SYNC_STARTED = "sync.started";
    public static final String SYNC_COMPLETED = "sync.completed";
    public static final String SYNC_ERROR = "sync.error";

    /** A new inbox message was found in Gmail and is being downloaded. */
    public static final String EMAIL_RECEIVED = "email.received";
    /** A new message is stored; payload is the inbox row (analysis still pending). */
    public static final String EMAIL_SAVED = "email.saved";
    /** Read/unread changed in Gmail. */
    public static final String EMAIL_UPDATED = "email.updated";
    /** Deleted or moved to Trash/Spam in Gmail. */
    public static final String EMAIL_DELETED = "email.deleted";

    public static final String ANALYSIS_STARTED = "email.analysis.started";
    public static final String ANALYSIS_COMPLETED = "email.analysis.completed";
    public static final String ANALYSIS_FAILED = "email.analysis.failed";
}
