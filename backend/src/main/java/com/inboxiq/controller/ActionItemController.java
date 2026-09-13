package com.inboxiq.controller;

import com.inboxiq.dto.ActionItemDto;
import com.inboxiq.dto.UpdateActionItemRequest;
import com.inboxiq.entity.ActionItem;
import com.inboxiq.entity.MailAccount;
import com.inboxiq.entity.User;
import com.inboxiq.exception.ResourceNotFoundException;
import com.inboxiq.mapper.EmailMapper;
import com.inboxiq.repository.ActionItemRepository;
import com.inboxiq.security.CurrentUserProvider;
import com.inboxiq.service.MailAccountService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/action-items")
public class ActionItemController {

    private final CurrentUserProvider currentUserProvider;
    private final MailAccountService mailAccountService;
    private final ActionItemRepository actionItemRepository;
    private final EmailMapper emailMapper;

    public ActionItemController(CurrentUserProvider currentUserProvider,
                                 MailAccountService mailAccountService,
                                 ActionItemRepository actionItemRepository,
                                 EmailMapper emailMapper) {
        this.currentUserProvider = currentUserProvider;
        this.mailAccountService = mailAccountService;
        this.actionItemRepository = actionItemRepository;
        this.emailMapper = emailMapper;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Page<ActionItemDto> list(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        User user = currentUserProvider.getCurrentUser();
        MailAccount account = mailAccountService.getActiveMailAccountOrThrow(user.getId());
        Pageable pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("deadline").ascending());
        return actionItemRepository.findByEmail_MailAccountIdOrderByDeadlineAsc(account.getId(), pageable)
                .map(emailMapper::toActionItemDto);
    }

    @PatchMapping("/{id}")
    @Transactional
    public ActionItemDto update(@PathVariable UUID id, @Valid @RequestBody UpdateActionItemRequest request) {
        User user = currentUserProvider.getCurrentUser();
        MailAccount account = mailAccountService.getActiveMailAccountOrThrow(user.getId());

        ActionItem item = actionItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Action item not found"));
        if (!item.getEmail().getMailAccount().getId().equals(account.getId())) {
            // Don't reveal that a differently-owned item exists.
            throw new ResourceNotFoundException("Action item not found");
        }
        item.setCompleted(request.completed());
        return emailMapper.toActionItemDto(actionItemRepository.save(item));
    }
}
