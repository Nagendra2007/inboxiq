package com.mailroom.dto;

import java.util.UUID;

/**
 * The signed-in user. Signing in and Gmail access are reported separately:
 * a user can be signed in while Gmail needs reconnecting.
 *
 * @param gmailConnected      a Gmail account is linked (not disconnected by the user)
 * @param gmailReauthRequired Google rejected Mailroom's Gmail grant; the user must reconnect Gmail
 * @param admin               whether this user may change app-wide settings (the AI provider)
 */
public record UserDto(UUID id, String email, String name, boolean gmailConnected,
                      boolean gmailReauthRequired, boolean admin) {}
