package com.inboxiq.gmail;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.Base64;
import com.google.api.services.gmail.Gmail;
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

    /** Lists message ids in INBOX, newest first, one page at a time. */
    public GmailListPage listInboxMessages(MailAccount account, String pageToken, int maxResults) {
        return search(account, "in:inbox", pageToken, maxResults);
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
     * Requires the gmail.modify scope; a message already gone from Gmail
     * (404) is treated as success rather than an error, since the end
     * state — not in the user's inbox — is already what we want.
     */
    public void trashMessage(MailAccount account, String messageId) {
        try {
            Gmail gmail = clientFactory.forAccount(account);
            gmail.users().messages().trash(USER_ID, messageId).execute();
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                log.info("Message already absent from Gmail when trashing; treating as already deleted");
                return;
            }
            throw translate(e);
        } catch (IOException e) {
            throw GmailIntegrationException.unavailable(e);
        }
    }

    private GmailIntegrationException translate(GoogleJsonResponseException e) {
        int status = e.getStatusCode();
        if (status == 401) {
            return GmailIntegrationException.reauthRequired(e);
        }
        if (status == 403 || status == 429) {
            return GmailIntegrationException.quotaExceeded(e);
        }
        return GmailIntegrationException.unavailable(e);
    }
}
