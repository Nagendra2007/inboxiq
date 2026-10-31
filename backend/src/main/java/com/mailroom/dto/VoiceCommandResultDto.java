package com.mailroom.dto;

import java.util.List;

/**
 * What was understood, offered back for confirmation rather than acted on.
 *
 * @param heardRecipient what the speaker called the person, so the UI can say
 *                       "you said Priya, but nobody by that name has written
 *                       to you" instead of silently leaving the field empty
 * @param candidates     ranked matches from the mailbox's own contacts; may
 *                       be empty, and may legitimately hold more than one
 * @param confident      false when the transcript was too garbled to split,
 *                       in which case the UI shows the words and lets the
 *                       user fix them by hand
 */
public record VoiceCommandResultDto(String heardRecipient, List<ContactDto> candidates,
                                    String subject, String instruction, boolean confident) {}
