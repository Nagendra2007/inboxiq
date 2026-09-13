package com.inboxiq.entity;

/**
 * The mailbox provider a {@link MailAccount} is linked to. Gmail is the only
 * one implemented today; the enum exists so a second provider (e.g. Outlook)
 * can be added without reshaping the schema.
 */
public enum MailProvider {
    GOOGLE
}
