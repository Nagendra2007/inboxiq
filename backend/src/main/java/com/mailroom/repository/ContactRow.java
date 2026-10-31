package com.mailroom.repository;

import java.time.Instant;

/**
 * One distinct {@code From} line the mailbox has received, with how often and
 * how recently. The address book nobody had to fill in: by the time anyone
 * dictates "write to Priya", Priya has almost certainly written to them.
 *
 * <p>Still the raw header value — {@code "Priya Shah" <priya@northwind.io>} —
 * because splitting it is the service's job, and the same person writing under
 * two display names has to collapse into one contact afterwards.
 */
public record ContactRow(String sender, Instant lastSeen, long messages) {
}
