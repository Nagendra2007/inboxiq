package com.mailroom.service;

import com.mailroom.entity.EmailMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an answer is allowed to cite.
 *
 * <p>The model picks which emails answer the question. It is never trusted
 * with what the emails are — the same division as the one-time code check,
 * and for the same reason: a model asked for identifiers will sometimes
 * produce well-formed ones it invented, and an invented citation is worse
 * than none because it looks like evidence.
 */
class ChatCitationTest {

    private static EmailMessage email(UUID id) {
        EmailMessage message = new EmailMessage();
        try {
            Field field = EmailMessage.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(message, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return message;
    }

    @Test
    void citationsThatWereInFrontOfItAreKept() {
        EmailMessage first = email(UUID.randomUUID());
        EmailMessage second = email(UUID.randomUUID());

        List<EmailMessage> verified = ChatService.verifyCitations(
                List.of(second.getId().toString(), first.getId().toString()), List.of(first, second));

        // Order is the model's: it was asked for most relevant first.
        assertThat(verified).containsExactly(second, first);
    }

    @Test
    void anInventedIdIsDiscarded() {
        EmailMessage real = email(UUID.randomUUID());

        List<EmailMessage> verified = ChatService.verifyCitations(
                List.of(UUID.randomUUID().toString()), List.of(real));

        assertThat(verified).isEmpty();
    }

    @Test
    void anInventedIdBesideARealOneLosesOnlyItself() {
        EmailMessage real = email(UUID.randomUUID());

        List<EmailMessage> verified = ChatService.verifyCitations(
                List.of(UUID.randomUUID().toString(), real.getId().toString()), List.of(real));

        assertThat(verified).containsExactly(real);
    }

    @Test
    void somethingThatIsNotAnIdAtAllIsIgnored() {
        EmailMessage real = email(UUID.randomUUID());

        List<EmailMessage> verified = ChatService.verifyCitations(
                List.of("the first one", "", "id: 3", real.getId().toString()), List.of(real));

        assertThat(verified).containsExactly(real);
    }

    @Test
    void theSameEmailCitedTwiceAppearsOnce() {
        EmailMessage real = email(UUID.randomUUID());
        String id = real.getId().toString();

        assertThat(ChatService.verifyCitations(List.of(id, id, id), List.of(real))).containsExactly(real);
    }

    @Test
    void citationsAreCappedSoAnAnswerCannotCiteEverythingItSaw() {
        List<EmailMessage> candidates = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> email(UUID.randomUUID()))
                .toList();
        List<String> all = candidates.stream().map(e -> e.getId().toString()).toList();

        // Citing the entire search is not an answer, and the cap keeps one
        // from being presented as though it were.
        assertThat(ChatService.verifyCitations(all, candidates)).hasSize(8);
    }

    @Test
    void nothingClaimedMeansNothingCited() {
        assertThat(ChatService.verifyCitations(null, List.of(email(UUID.randomUUID())))).isEmpty();
        assertThat(ChatService.verifyCitations(List.of(), List.of(email(UUID.randomUUID())))).isEmpty();
    }
}
