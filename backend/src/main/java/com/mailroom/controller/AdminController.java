package com.mailroom.controller;

import com.mailroom.dto.AdministratorsDto;
import com.mailroom.security.AdminAccess;
import com.mailroom.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-only views of how the deployment is administered. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CurrentUserProvider currentUserProvider;
    private final AdminAccess adminAccess;

    public AdminController(CurrentUserProvider currentUserProvider, AdminAccess adminAccess) {
        this.currentUserProvider = currentUserProvider;
        this.adminAccess = adminAccess;
    }

    /** Who the administrators are (403 for everyone else). */
    @GetMapping("/administrators")
    public AdministratorsDto administrators() {
        adminAccess.requireAdmin(currentUserProvider.getCurrentUser());
        return adminAccess.describe();
    }
}
