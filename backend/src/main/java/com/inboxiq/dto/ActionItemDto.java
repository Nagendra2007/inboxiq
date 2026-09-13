package com.inboxiq.dto;

import java.time.LocalDate;
import java.util.UUID;

public record ActionItemDto(
        UUID id,
        UUID emailId,
        String emailSubject,
        String description,
        LocalDate deadline,
        boolean completed
) {}
