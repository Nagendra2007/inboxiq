package com.inboxiq.gmail;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.Base64;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.HistoryLabelAdded;
import com.google.api.services.gmail.model.HistoryLabelRemoved;
import com.google.api.services.gmail.model.HistoryMessageAdded;
import com.google.api.services.gmail.model.HistoryMessageDeleted;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.exception.GmailIntegrationException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Thin, focused wrapper around the Gmail API: list/search/get/send/trash.
 * Every public method translates Google's checked {@link IOException}s into
 * the app's own {@link GmailIntegrationException} variants (reauth-required,
 * quota-exceeded, unavailable) so callers never need to know about Google's
 * exception hierarchy.
 */
@Component
public class GmailInboxClient {

    private static final Logger log = LoggerFactory.getLogger(GmailInboxClient.class);
    private static final String USER_ID = "me"; // Gmail API convention for "the authenticated user"

    private final GmailClientFactory clientFactory;
    private final GmailMessageParser messageParser;

    public GmailInboxClient(GmailClientFactory clientFactory, GmailMessageParser messageParser) {
        this.clientFactory = clientFactory;
        this.messageParser = messageParser;
    }

    /** Gmail returns this when a history id is older than the history it keeps (about a week, sometimes less). */
    public static class HistoryExpiredException extends RuntimeException {
        public HistoryExpiredException(Throwable cause) {
            super("Gmail history checkpoint has expired", cause);
        }
    }

    private static final String LABEL_INBOX = "INBOX";
    private static final List<String> SYNC_HISTORY_TYPES =
            List.of("messageAdded", "messageDeleted", "labelAdded", "labelRemoved");

    /**
     * An API client bound to one valid access token, for a sync pass that
     * makes many calls (and downloads in parallel) without refreshing the
     * token once per call.
     */
    public Gmail open(MailAccount account) {
        return clientFactory.forAccount(account);
    }

    /** The mailbox's current history id — the checkpoint a first sync starts from. */
    public String currentHistoryId(Gmail gmail) {
        try {
            BigInteger historyId = gmail.users().getProfile(USER_ID).execute().getHistoryId();
            return historyId == null ? null : historyId.toString();
        } catch (GoogleJsonResponseException e) {
            throw translate(e);
        } catch (IOException e) {
            throw GmailIntegrationException.unavailable(e);
        }
    }

    /** Ids of the newest {@code limit} INBOX messages, newest first. One call, whatever the mailbox size. */
    public List<String> latestInboxMessageIds(Gmail gmail, int limit) {
        try {
            ListMessagesResponse response = gmail.users().messages().list(USER_ID)
                    .setLabelIds(List.of(LABEL_INBOX))
                    .setMaxResults((long) limit)
                    .execute();
            return response.getMessages() == null ? List.of()
                    : response.getMessages().stream().map(Message::getId).toList();
        } catch (GoogleJsonResponseException e) {
            throw translate(e);
        } catch (IOException e) {
            throw GmailIntegrationException.unavailable(e);
        }
    }

    /**
     * One page of changes after {@code startHistoryId}.
     *
     * @throws HistoryExpiredException when Gmail no longer has history that old
     */
    public GmailHistoryPage listHistory(Gmail gmail, String startHistoryId, String pageToken) {
        try {
            Gmail.Users.History.List request = gmail.users().history().list(USER_ID)
                    .setStartHistoryId(new BigInteger(startHistoryId))
                    .setHistoryTypes(SYNC_HISTORY_TYPES)
                    .setMaxResults(500L);
            if (pageToken != null && !pageToken.isBlank()) {
                request.setPageToken(pageToken);
            }
            ListHistoryResponse response = request.execute();

            List<GmailHistoryPage.Change> changes = new ArrayList<>();
            if (response.getHistory() != null) {
                for (History record : response.getHistory()) {
                    addChanges(record, changes);
                }
            }
            String historyId = response.getHistoryId() == null ? null : response.getHistoryId().toString();
            return new GmailHistoryPage(changes, response.getNextPageToken(), historyId);
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                throw new HistoryExpiredException(e);
            }
            throw translate(e);
        } catch (IOException e) {
            throw GmailIntegrationException.unavailable(e);
        }
    }

    private static void addChanges(History record, List<GmailHistoryPage.Change> changes) {
        if (record.getMessagesAdded() != null) {
            for (HistoryMessageAdded added : record.getMessagesAdded()) {
                Message m = added.getMessage();
                if (m != null) changes.add(new GmailHistoryPage.Change(
                        GmailHistoryPage.Type.MESSAGE_ADDED, m.getId(), m.getLabelIds()));
            }
        }
        if (record.getLabelsAdded() != null) {
            for (HistoryLabelAdded added : record.getLabelsAdded()) {
                if (added.getMessage() != null) changes.add(new GmailHistoryPage.Change(
                        GmailHistoryPage.Type.LABELS_ADDED, added.getMessage().getId(), added.getLabelIds()));
            }
        }
        if (record.getLabelsRemoved() != null) {
            for (HistoryLabelRemoved removed : record.getLabelsRemoved()) {
                if (removed.getMessage() != null) changes.add(new GmailHistoryPage.Change(
                        GmailHistoryPage.Type.LABELS_REMOVED, removed.getMessage().getId(), removed.getLabelIds()));
            }
        }
        if (record.getMessagesDeleted() != null) {
            for (HistoryMessageDeleted deleted : record.getMessagesDeleted()) {
                if (deleted.getMessage() != null) changes.add(new GmailHistoryPage.Change(
                        GmailHistoryPage.Type.MESSAGE_DELETED, deleted.getMessage().getId(), List.of()));
            }
        }
    }

    /** Fetches and parses one message, or returns null if it no longer exists in Gmail. */
    public ParsedGmailMessage getMessageIfPresent(Gmail gmail, String messageId) {
        try {
            Message message = gmail.users().messages().get(USER_ID, messageId).setFormat("full").execute();
            return messageParser.parse(message);
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) return null;
            throw translate(e);
        } catch (IOException e) {
            throw GmailIntegrationException.unavailable(e);
        }
    }

    /**
     * Runs a Gmail search query (Gmail's own query syntax — from:, subject:,
     * after:, is:unread, etc.) so we page through only the messages that
     * match, instead of pulling the whole mailbox and filtering locally.
     */
    public GmailListPage search(MailAccount account, String gmailQuery, String pageToken, int maxResults) {
        try {
            Gmail gmail = clientFactory.forAccount(account);
            Gmail.Users.Messages.List request = gmail.users().messages().list(USER_ID)
                    .setQ(gmailQuery)
                    .setMaxResults((long) maxResults);
            if (pageToken != null && !pageToken.isBlank()) {
                request.setPageToken(pageToken);
            }
            ListMessagesResponse response = request.execute();
            List<String> ids = response.getMessages() == null ? List.of()
                    : response.getMessages().stream().map(com.google.api.services.gmail.model.Message::getId).toList();
            return new GmailListPage(ids, response.getNextPageToken());
        } catch (GoogleJsonResponseException e) {
            throw translate(e);
        } catch (IOException e) {
            throw GmailIntegrationException.unavailable(e);
        }
    }

    /** Fetches one message in full (headers + body parts), already parsed. */
    public ParsedGmailMessage getMessage(MailAccount account, String messageId) {
        try {
            Gmail gmail = clientFactory.forAccount(account);
            Message message = gmail.users().messages().get(USER_ID, messageId)
                    .setFormat("full")
                    .execute();
            return messageParser.parse(message);
        } catch (GoogleJsonResponseException e) {
            throw translate(e);
        } catch (IOException e) {
            throw GmailIntegrationException.unavailable(e);
        }
    }

    /** Fetches every message in a thread, in Gmail's own order, already parsed. */
    public List<ParsedGmailMessage> getThread(MailAccount account, String threadId) {
        try {
            Gmail gmail = clientFactory.forAccount(account);
            var thread = gmail.users().threads().get(USER_ID, threadId).setFormat("full").execute();
            if (thread.getMessages() == null) return List.of();
            return thread.getMessages().stream().map(messageParser::parse).toList();
        } catch (GoogleJsonResponseException e) {
            throw translate(e);
        } catch (IOException e) {
            throw GmailIntegrationException.unavailable(e);
        }
    }

    /**
     * Sends a reply. Only ever called after the user has explicitly clicked
     * Send on a reviewed/edited draft — see {@code ReplyService#sendReply}.
     * Threading headers (In-Reply-To/References + Gmail's threadId) are set
     * so the reply lands in the same conversation in both Gmail and the
     * recipient's client, rather than as a new, disconnected message.
     */
    public String sendReply(MailAccount account, OutgoingReply reply) {
        try {
            Gmail gmail = clientFactory.forAccount(account);

            Properties props = new Properties();
            Session mailSession = Session.getDefaultInstance(props, null);
            MimeMessage mimeMessage = new MimeMessage(mailSession);
            mimeMessage.setFrom(new InternetAddress(account.getProviderEmail()));
            mimeMessage.addRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(reply.toAddress()));
            mimeMessage.setSubject(reply.subject());
            mimeMessage.setText(reply.bodyText(), "UTF-8");
            if (reply.inReplyToMessageId() != null) {
                mimeMessage.setHeader("In-Reply-To", reply.inReplyToMessageId());
            }
            if (reply.references() != null) {
                mimeMessage.setHeader("References", reply.references());
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            mimeMessage.writeTo(buffer);
            String encoded = Base64.encodeBase64URLSafeString(buffer.toByteArray());

            Message gmailMessage = new Message();
            gmailMessage.setRaw(encoded);
            if (reply.gmailThreadId() != null) {
                gmailMessage.setThreadId(reply.gmailThreadId());
            }

            Message sent = gmail.users().messages().send(USER_ID, gmailMessage).execute();
            log.info("Sent reply via Gmail, new message id recorded");
            return sent.getId();

        } catch (GoogleJsonResponseException e) {
            throw translate(e);
        } catch (IOException | jakarta.mail.MessagingException e) {
            throw GmailIntegrationException.unavailable(e);
        }
    }

    /**
     * Moves a message to Trash in the user's real Gmail — the same,
     * reversible action as clicking Gmail's own trash icon (not a permanent
     * delete). Called before InboxIQ removes its own local copy, so
     * "delete" in the app means the same thing as "delete" in Gmail.
     * Requires the gmail.modify scope. Returns true when Gmail moved the
     * message, false when Gmail no longer had it (404) — already deleted
     * there or in another client. Both leave the end state we want, so
     * neither is an error, but only the first is worth telling the user
     * their mail was moved to Trash.
     */
    public boolean trashMessage(MailAccount account, String messageId) {
        try {
            Gmail gmail = clientFactory.forAccount(account);
            gmail.users().messages().trash(USER_ID, messageId).execute();
            return true;
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                // Deleted in Gmail itself, or in another client, since the last
                // sync. Not an error — but the caller shouldn't tell the user
                // it moved anything to Trash either.
                log.info("Message already absent from Gmail when trashing; treating as already deleted");
                return false;
            }
            throw translate(e);
        } catch (IOException e) {
            throw GmailIntegrationException.unavailable(e);
        }
    }

    private GmailIntegrationException translate(GoogleJsonResponseException e) {
        int status = e.getStatusCode();
        if (status == 401 || (status == 403 && isMissingPermission(e))) {
            return GmailIntegrationException.reauthRequired(e);
        }
        if (status == 403 || status == 429) {
            return GmailIntegrationException.quotaExceeded(e);
        }
        return GmailIntegrationException.unavailable(e);
    }

    /**
     * A 403 is usually a rate limit, but it is also what Gmail returns when
     * the user unticked a Gmail permission on Google's consent screen. That
     * one needs a reconnect, not a retry.
     */
    private static boolean isMissingPermission(GoogleJsonResponseException e) {
        if (e.getDetails() == null) return false;
        String message = e.getDetails().getMessage();
        if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("insufficient")) return true;
        return e.getDetails().getErrors() != null && e.getDetails().getErrors().stream()
                .anyMatch(info -> "insufficientPermissions".equals(info.getReason()));
    }
}
