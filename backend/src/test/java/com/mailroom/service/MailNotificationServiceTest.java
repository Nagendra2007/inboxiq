package com.mailroom.service;

import com.mailroom.entity.EmailAnalysis;
import com.mailroom.entity.EmailMessage;
import com.mailroom.entity.RiskLevel;
import com.mailroom.repository.EmailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What reaches the phone, and what it says.
 *
 * <p>The interesting cases are the ones where something is missing: no
 * notification line, no summary, no subject. A notification that says
 * "null" or nothing at all is worse than one that says the subject, and
 * nothing in the type system stops that from happening.
 */
class MailNotificationServiceTest {

    private static final UUID USER = UUID.randomUUID();

    private PushNotificationService push;
    private DeviceNotificationService devices;
    private EmailRepository emails;
    private MailNotificationService service;

    @BeforeEach
    void setUp() {
        push = mock(PushNotificationService.class);
        devices = mock(DeviceNotificationService.class);
        emails = mock(EmailRepository.class);
        when(push.isEnabled()).thenReturn(true);
        when(devices.isEnabled()).thenReturn(true);
        service = new MailNotificationService(push, devices, emails);
    }

    private UUID given(EmailMessage email) {
        UUID id = UUID.randomUUID();
        when(emails.findById(id)).thenReturn(Optional.of(email));
        return id;
    }

    private static EmailMessage mail(String sender, String subject, EmailAnalysis analysis) {
        EmailMessage email = new EmailMessage();
        email.setSender(sender);
        email.setSubject(subject);
        email.setAnalysis(analysis);
        return email;
    }

    private static EmailAnalysis analysis(String notificationText, String summary) {
        EmailAnalysis analysis = new EmailAnalysis();
        analysis.setNotificationText(notificationText);
        analysis.setSummary(summary);
        analysis.setRiskLevel(RiskLevel.LOW);
        return analysis;
    }

    @Test
    void ordinaryMailNotifies() {
        UUID id = given(mail("\"Priya Shah\" <priya@x.io>", "Lunch?",
                analysis("Lunch on Thursday, confirm by tonight", "Priya is asking about lunch.")));

        service.notifyIfNoteworthy(USER, id);

        verify(devices).notifyUser(USER, "Priya Shah",
                "Lunch on Thursday, confirm by tonight", "/inbox?email=" + id);
    }

    @Test
    void theAnalysisLineIsPreferredOverTheSummary() {
        UUID id = given(mail("bank@x.io", "Statement",
                analysis("Card ending 4471 was charged 2,310",
                        "A notification from your bank about recent account activity.")));

        service.notifyIfNoteworthy(USER, id);

        verify(devices).notifyUser(eq(USER), any(),
                eq("Card ending 4471 was charged 2,310"), any());
    }

    @Test
    void withoutAnAnalysisLineTheSummaryStandsIn() {
        UUID id = given(mail("bank@x.io", "Statement",
                analysis(null, "Your January statement is ready.")));

        service.notifyIfNoteworthy(USER, id);

        verify(devices).notifyUser(eq(USER), any(), eq("Your January statement is ready."), any());
    }

    @Test
    void withNeitherTheSubjectStandsIn() {
        UUID id = given(mail("bank@x.io", "Statement ready", analysis(null, "   ")));

        service.notifyIfNoteworthy(USER, id);

        verify(devices).notifyUser(eq(USER), any(), eq("Statement ready"), any());
    }

    @Test
    void withNothingAtAllItStillSaysSomethingReadable() {
        UUID id = given(mail("bank@x.io", null, analysis(null, null)));

        service.notifyIfNoteworthy(USER, id);

        verify(devices).notifyUser(eq(USER), any(), eq("(no subject)"), any());
    }

    @Test
    void aOneTimeCodeTakesTheCodePathInsteadOfTheSummary() {
        EmailAnalysis withCode = analysis("A sign-in code arrived", "GitHub sent a code.");
        withCode.setOtpCode("418293");
        UUID id = given(mail("GitHub <noreply@github.com>", "Your code", withCode));

        service.notifyIfNoteworthy(USER, id);

        verify(devices).notifyOtp(USER, "418293", "GitHub", "/inbox?email=" + id);
        verify(devices, never()).notifyUser(any(), any(), any(), any());
    }

    @Test
    void aLikelyScamSaysSoInTheTitle() {
        EmailAnalysis risky = analysis("Asks you to confirm your password", null);
        risky.setRiskLevel(RiskLevel.HIGH);
        UUID id = given(mail("security@paypa1.com", "Verify now", risky));

        service.notifyIfNoteworthy(USER, id);

        verify(devices).notifyUser(eq(USER), eq("Possible scam: security@paypa1.com"), any(), any());
    }

    @Test
    void mailAlreadyReadDoesNotNotify() {
        EmailMessage email = mail("a@b.io", "Hello", analysis("Something", null));
        email.setRead(true);
        UUID id = given(email);

        service.notifyIfNoteworthy(USER, id);

        verify(devices, never()).notifyUser(any(), any(), any(), any());
        verify(push, never()).notifyUser(any(), any(), any(), any(), any());
    }

    @Test
    void anEmailWithNoAnalysisYetWaitsForOne() {
        UUID id = given(mail("a@b.io", "Hello", null));

        service.notifyIfNoteworthy(USER, id);

        verify(devices, never()).notifyUser(any(), any(), any(), any());
    }

    @Test
    void nothingIsLookedUpWhenNoChannelIsConfigured() {
        when(push.isEnabled()).thenReturn(false);
        when(devices.isEnabled()).thenReturn(false);

        service.notifyIfNoteworthy(USER, UUID.randomUUID());

        verifyNoInteractions(emails);
    }
}
