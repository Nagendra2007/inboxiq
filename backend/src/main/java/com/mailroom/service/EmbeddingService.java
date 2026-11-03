package com.mailroom.service;

import com.mailroom.ai.EmbeddingClient;
import com.mailroom.entity.EmailAnalysis;
import com.mailroom.entity.EmailEmbedding;
import com.mailroom.entity.EmailMessage;
import com.mailroom.entity.MailAccount;
import com.mailroom.repository.EmailEmbeddingRepository;
import com.mailroom.repository.EmailRepository;
import com.mailroom.repository.MailAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeping a mailbox's vectors up to date, and using them.
 *
 * <p>Embedding happens in the background and a batch at a time, because a
 * mailbox is tens of thousands of messages and nobody should wait for it.
 * Nothing depends on it having finished: a question asked while half the
 * mailbox is embedded is answered from the half that is, plus keyword
 * search over the rest.
 *
 * <p>What gets embedded is the sender, subject and the opening of the
 * message — not the whole thing. A newsletter's twenty paragraphs of
 * footer drown the one line that says what it is, and the model charges
 * for every word of it.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** Per scheduled pass, per mailbox. Bounded so it never monopolises the provider. */
    private static final int BATCH = 64;

    /** Enough of an email to tell what it is. */
    private static final int TEXT_CHARS = 1200;

    private final EmailRepository emailRepository;
    private final EmailEmbeddingRepository embeddings;
    private final MailAccountRepository mailAccountRepository;
    private final EmbeddingClient client;

    public EmbeddingService(EmailRepository emailRepository,
                            EmailEmbeddingRepository embeddings,
                            MailAccountRepository mailAccountRepository,
                            EmbeddingClient client) {
        this.emailRepository = emailRepository;
        this.embeddings = embeddings;
        this.mailAccountRepository = mailAccountRepository;
        this.client = client;
    }

    public boolean isEnabled() {
        return client.isEnabled();
    }

    /**
     * Embeds a batch of whatever is not embedded yet, newest first.
     *
     * <p>Newest first because recent mail is what gets asked about, so a
     * mailbox becomes useful to search long before it is finished.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void embedPending() {
        if (!isEnabled()) return;
        for (MailAccount account : mailAccountRepository.findAll()) {
            if (!account.isActive()) continue;
            try {
                embedBatch(account.getId());
            } catch (RuntimeException e) {
                log.warn("Embedding pass failed for account id={}: {}", account.getId(), e.toString());
            }
        }
    }

    @Transactional
    public int embedBatch(UUID mailAccountId) {
        List<UUID> pending = embeddings.findUnembedded(mailAccountId, client.model(), PageRequest.of(0, BATCH));
        if (pending.isEmpty()) return 0;

        List<EmailMessage> emails = emailRepository.findAllById(pending);
        List<String> texts = emails.stream().map(EmbeddingService::textOf).toList();

        List<float[]> vectors = client.embed(texts);
        // An empty result is a failed call, not an empty mailbox. Writing
        // nothing means the next pass tries the same emails again, which
        // is what should happen.
        if (vectors.size() != emails.size()) return 0;

        List<EmailEmbedding> rows = new ArrayList<>(emails.size());
        for (int i = 0; i < emails.size(); i++) {
            EmailEmbedding row = new EmailEmbedding();
            row.setEmailId(emails.get(i).getId());
            row.setModel(client.model());
            row.setDimensions(vectors.get(i).length);
            row.setVector(EmbeddingClient.toBytes(vectors.get(i)));
            rows.add(row);
        }
        embeddings.saveAll(rows);
        log.debug("Embedded {} emails for account id={}", rows.size(), mailAccountId);
        return rows.size();
    }

    /**
     * Reorders candidates by how close they are to the question.
     *
     * <p>Keyword search decides what is <em>eligible</em>; this decides
     * what is <em>relevant</em>. An email that matched on one incidental
     * word sinks, and one that matched on several of the right ones rises
     * — which matters because only the top two dozen are ever shown to the
     * answering model.
     *
     * <p>Candidates with no vector yet keep their place rather than being
     * dropped. A mailbox is embedded gradually, and silently hiding
     * everything not yet processed would make the feature worse the
     * earlier it is used.
     */
    public List<EmailMessage> rerank(String question, List<EmailMessage> candidates) {
        if (!isEnabled() || candidates.size() < 2) return candidates;

        List<float[]> questionVector = client.embed(List.of(question));
        if (questionVector.isEmpty()) return candidates;

        Map<UUID, float[]> byEmail = new HashMap<>();
        for (EmailEmbedding row : embeddings.findByEmailIdInAndModel(
                candidates.stream().map(EmailMessage::getId).toList(), client.model())) {
            byEmail.put(row.getEmailId(), EmbeddingClient.fromBytes(row.getVector()));
        }
        if (byEmail.isEmpty()) return candidates;

        record Scored(EmailMessage email, double score, int original) {}
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            EmailMessage email = candidates.get(i);
            float[] vector = byEmail.get(email.getId());
            // Unembedded mail scores at the midpoint: not promoted over a
            // genuine match, not buried beneath a poor one.
            double score = vector == null ? 0.0 : EmbeddingClient.similarity(questionVector.get(0), vector);
            scored.add(new Scored(email, score, i));
        }

        scored.sort((a, b) -> {
            int byScore = Double.compare(b.score(), a.score());
            // Ties keep the order they arrived in, which is newest first.
            return byScore != 0 ? byScore : Integer.compare(a.original(), b.original());
        });
        return scored.stream().map(Scored::email).toList();
    }

    /** Sender, subject and the opening of the message — enough to tell what it is. */
    static String textOf(EmailMessage email) {
        StringBuilder text = new StringBuilder();
        if (email.getSender() != null) text.append("From: ").append(email.getSender()).append('\n');
        if (email.getSubject() != null) text.append("Subject: ").append(email.getSubject()).append('\n');

        EmailAnalysis analysis = email.getAnalysis();
        if (analysis != null && analysis.getSummary() != null && !analysis.getSummary().isBlank()) {
            text.append(analysis.getSummary());
        } else if (email.getBodyText() != null) {
            text.append(email.getBodyText());
        } else if (email.getSnippet() != null) {
            text.append(email.getSnippet());
        }

        String collapsed = text.toString().replaceAll("\\s+", " ").trim();
        return collapsed.length() <= TEXT_CHARS ? collapsed : collapsed.substring(0, TEXT_CHARS);
    }
}
