package com.inboxiq.dto;

import java.util.List;

/**
 * Who the administrators are, and why.
 *
 * @param source "ADMIN_EMAILS" when set by that environment variable, or
 *               "FIRST_ACCOUNT" when it's empty and the first account on the
 *               deployment is the administrator
 */
public record AdministratorsDto(String source, List<Admin> admins) {

    /** @param signedIn whether this email has an InboxIQ account yet */
    public record Admin(String email, boolean signedIn) {}
}
