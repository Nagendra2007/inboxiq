package com.mailroom.service;

import com.mailroom.ai.AiClient;
import com.mailroom.ai.AiConfig;
import com.mailroom.ai.AiResponseParser;
import com.mailroom.ai.dto.ComposeCommandAiResult;
import com.mailroom.ai.prompt.ComposeCommandPrompt;
import com.mailroom.ai.prompt.PromptPair;
import com.mailroom.repository.ContactRow;
import com.mailroom.repository.EmailRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns "write to Priya about the contract delay" into the three fields the
 * composer already takes.
 *
 * <p>Two steps, kept apart on purpose. The model splits the sentence; this
 * class matches the name against people who have actually written to this
 * mailbox. The model never sees the contact list, because a model shown a
 * list of addresses next to a half-heard name will happily produce the one
 * that looks right, and an email sent to the wrong person is not a mistake
 * you can take back.
 *
 * <p>So nothing here resolves a recipient on its own authority. It returns
 * candidates, ranked, and the UI makes the user confirm — which is also the
 * honest design, since speech recognition is least reliable on exactly the
 * part that matters most.
 */
@Service
public class VoiceComposeService {

    /** How far back the address book goes. Far more than anyone dictates to. */
    private static final int CONTACT_SCAN = 500;

    /** More than three choices is not a confirmation, it is a search result. */
    private static final int MAX_CANDIDATES = 3;

    private static final Pattern ADDRESSED = Pattern.compile("^\\s*\"?([^\"<]*?)\"?\\s*<([^>]+)>\\s*$");
    private static final Pattern LOOKS_LIKE_EMAIL = Pattern.compile("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$");

    private final EmailRepository emailRepository;
    private final AiClient aiClient;
    private final AiResponseParser parser;
    private final AiSettingsService aiSettingsService;

    public VoiceComposeService(EmailRepository emailRepository, AiClient aiClient,
                               AiResponseParser parser, AiSettingsService aiSettingsService) {
        this.emailRepository = emailRepository;
        this.aiClient = aiClient;
        this.parser = parser;
        this.aiSettingsService = aiSettingsService;
    }

    /** A person this mailbox has heard from. */
    public record Contact(String name, String email) {}

    /**
     * @param heardRecipient what the speaker called them, kept so the UI can
     *                       say "you said Priya" when nothing matched
     */
    public record ParsedCommand(String heardRecipient, List<Contact> candidates,
                                String subject, String instruction, boolean confident) {}

    public ParsedCommand parse(UUID mailAccountId, String spokenText) {
        PromptPair prompt = ComposeCommandPrompt.build(spokenText);
        AiConfig ai = aiSettingsService.current();
        ComposeCommandAiResult result = parser.parseComposeCommand(
                aiClient.complete(ai, prompt.systemPrompt(), prompt.userPrompt(), true));

        if (result.instruction() == null) {
            return new ParsedCommand(null, List.of(), null, null, false);
        }
        return new ParsedCommand(
                result.recipient(),
                resolveRecipient(mailAccountId, result.recipient()),
                result.subject(),
                result.instruction(),
                result.confident()
        );
    }

    /** The mailbox's address book, newest correspondent first. */
    public List<Contact> contacts(UUID mailAccountId) {
        List<ContactRow> rows = emailRepository.findContacts(mailAccountId, PageRequest.of(0, CONTACT_SCAN));
        // One person can appear under several display names. Keep the first,
        // which is the most recent, and let later rows only fill in a name
        // the earlier one lacked.
        Map<String, Contact> byAddress = new LinkedHashMap<>();
        for (ContactRow row : rows) {
            Contact contact = split(row.sender());
            if (contact == null) continue;
            byAddress.merge(contact.email().toLowerCase(Locale.ROOT), contact,
                    (first, next) -> first.name().isBlank() ? next : first);
        }
        return List.copyOf(byAddress.values());
    }

    /**
     * Ranked guesses at who "Priya" is. An exact address wins outright; after
     * that a whole first name beats a prefix, and a prefix beats appearing
     * anywhere in the string. Recency breaks every tie, because the person
     * you are dictating about is usually the person you heard from this week.
     */
    public List<Contact> resolveRecipient(UUID mailAccountId, String heard) {
        if (heard == null || heard.isBlank()) return List.of();
        String needle = heard.trim().toLowerCase(Locale.ROOT);

        if (LOOKS_LIKE_EMAIL.matcher(needle).matches()) {
            // They spelled out an address. Take it, but still prefer the
            // stored form so the name comes along for the confirmation step.
            return contacts(mailAccountId).stream()
                    .filter(c -> c.email().equalsIgnoreCase(needle))
                    .findFirst()
                    .map(List::of)
                    .orElseGet(() -> List.of(new Contact("", needle)));
        }

        List<Contact> contacts = contacts(mailAccountId);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.get(i);
            int score = score(c, needle);
            if (score > 0) scored.add(new Scored(c, score, i));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparingInt(Scored::recency));
        return scored.stream().limit(MAX_CANDIDATES).map(Scored::contact).toList();
    }

    private record Scored(Contact contact, int score, int recency) {}

    private static int score(Contact contact, String needle) {
        String name = contact.name().toLowerCase(Locale.ROOT);
        String local = contact.email().toLowerCase(Locale.ROOT).split("@")[0];

        // An exact match has no length floor: Jo, Li and Bo are names.
        if (name.equals(needle)) return 100;
        for (String part : name.split("\\s+")) {
            if (part.equals(needle)) return 90;
        }
        if (local.equals(needle)) return 85;

        // Anything short of exact does, though. "an" is a prefix of Ananya
        // and a substring of Joanna, and offering both is not a confirmation
        // step, it is a quiz. A fragment that short out of speech recognition
        // is noise more often than it is a name.
        if (needle.length() < 3) return 0;

        if (name.startsWith(needle)) return 70;
        for (String part : name.split("\\s+")) {
            if (part.startsWith(needle)) return 60;
        }
        if (local.startsWith(needle)) return 50;
        if (needle.length() >= 4 && (name.contains(needle) || local.contains(needle))) return 30;
        return 0;
    }

    /** {@code "Priya Shah" <priya@x.io>} into its two halves. */
    private static Contact split(String sender) {
        if (sender == null || sender.isBlank()) return null;
        Matcher m = ADDRESSED.matcher(sender);
        if (m.matches()) {
            String email = m.group(2).trim();
            if (email.isEmpty()) return null;
            return new Contact(m.group(1).trim(), email);
        }
        String bare = sender.trim();
        return bare.contains("@") ? new Contact("", bare) : null;
    }
}
