package com.mailroom.service;

import com.mailroom.ai.AiClient;
import com.mailroom.ai.AiResponseParser;
import com.mailroom.repository.ContactRow;
import com.mailroom.repository.EmailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Turning a heard name into an address is the step that can put an email in
 * front of the wrong person, so this is mostly about ranking and about what
 * it refuses to guess at.
 */
class VoiceComposeServiceTest {

    private static final UUID ACCOUNT = UUID.randomUUID();

    private EmailRepository emails;
    private VoiceComposeService service;

    @BeforeEach
    void setUp() {
        emails = mock(EmailRepository.class);
        service = new VoiceComposeService(emails, mock(AiClient.class),
                mock(AiResponseParser.class), mock(AiSettingsService.class));
    }

    /** Senders newest first, an hour apart, the way the query returns them. */
    private void inbox(String... senders) {
        Instant now = Instant.now();
        List<ContactRow> rows = new ArrayList<>();
        for (int i = 0; i < senders.length; i++) {
            rows.add(new ContactRow(senders[i], now.minusSeconds(i * 3600L), 1));
        }
        when(emails.findContacts(any(UUID.class), any(Pageable.class))).thenReturn(rows);
    }

    private List<String> resolve(String heard) {
        return service.resolveRecipient(ACCOUNT, heard).stream()
                .map(VoiceComposeService.Contact::email).toList();
    }

    // --- the address book itself -------------------------------------------

    @Test
    void collapsesOnePersonWritingUnderTwoDisplayNames() {
        inbox("\"Priya Shah\" <priya@northwind.io>", "\"Priya\" <priya@northwind.io>");

        assertThat(service.contacts(ACCOUNT)).singleElement()
                .satisfies(c -> {
                    assertThat(c.email()).isEqualTo("priya@northwind.io");
                    // Most recent spelling, not the longest.
                    assertThat(c.name()).isEqualTo("Priya Shah");
                });
    }

    @Test
    void keepsAnameWhenTheMoreRecentRowHasNone() {
        inbox("<priya@northwind.io>", "\"Priya Shah\" <priya@northwind.io>");

        assertThat(service.contacts(ACCOUNT)).singleElement()
                .extracting(VoiceComposeService.Contact::name).isEqualTo("Priya Shah");
    }

    @Test
    void ignoresSendersWithNoAddressAtAll() {
        inbox("Mailer Daemon", "\"Real Person\" <real@example.com>");

        assertThat(service.contacts(ACCOUNT)).singleElement()
                .extracting(VoiceComposeService.Contact::email).isEqualTo("real@example.com");
    }

    // --- resolving what was heard ------------------------------------------

    @Test
    void matchesAFirstNameOutOfAFullName() {
        inbox("\"Priya Shah\" <priya@northwind.io>", "\"Sam Patel\" <sam@acme.test>");

        assertThat(resolve("Priya")).containsExactly("priya@northwind.io");
    }

    @Test
    void offersEverySamRatherThanPickingOne() {
        inbox("\"Sam Patel\" <sam.patel@acme.test>", "\"Sam Okafor\" <sam.o@other.test>");

        // Two people called Sam is exactly when a silent choice is dangerous.
        assertThat(resolve("Sam")).containsExactly("sam.patel@acme.test", "sam.o@other.test");
    }

    @Test
    void prefersAWholeNameOverAPrefixOfALongerOne() {
        inbox("\"Samantha Reed\" <samantha@acme.test>", "\"Sam Patel\" <sam@acme.test>");

        // Samantha is more recent, but "Sam" is Sam's whole name.
        assertThat(resolve("Sam")).first().isEqualTo("sam@acme.test");
    }

    @Test
    void breaksTiesByWhoWroteMostRecently() {
        inbox("\"Priya Shah\" <priya.new@acme.test>", "\"Priya Shah\" <priya.old@other.test>");

        assertThat(resolve("Priya")).first().isEqualTo("priya.new@acme.test");
    }

    @Test
    void takesAnAddressTheSpeakerSpelledOut() {
        inbox("\"Priya Shah\" <priya@northwind.io>");

        assertThat(resolve("priya@northwind.io")).containsExactly("priya@northwind.io");
    }

    @Test
    void acceptsASpelledOutAddressNobodyHasWrittenFrom() {
        inbox("\"Priya Shah\" <priya@northwind.io>");

        assertThat(resolve("someone@elsewhere.test")).containsExactly("someone@elsewhere.test");
    }

    @Test
    void findsNobodyRatherThanGuessing() {
        inbox("\"Priya Shah\" <priya@northwind.io>");

        // The UI then says "you said Dheeraj" and leaves the field empty,
        // which is the only honest outcome.
        assertThat(resolve("Dheeraj")).isEmpty();
    }

    @Test
    void doesNotMatchOnAFragmentTooShortToMeanAnything() {
        inbox("\"Priya Shah\" <priya@northwind.io>", "\"Ananya Rao\" <ananya@acme.test>");

        // "an" appears inside both; matching on it would offer the whole
        // address book as candidates.
        assertThat(resolve("an")).isEmpty();
    }

    @Test
    void offersAtMostThreeChoices() {
        inbox("\"Sam A\" <a@x.test>", "\"Sam B\" <b@x.test>",
              "\"Sam C\" <c@x.test>", "\"Sam D\" <d@x.test>");

        assertThat(resolve("Sam")).hasSize(3);
    }

    @Test
    void handlesAnEmptyMailbox() {
        inbox();

        assertThat(service.contacts(ACCOUNT)).isEmpty();
        assertThat(resolve("Priya")).isEmpty();
    }
}
