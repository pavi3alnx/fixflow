package com.fixflow.backend.notification;

import com.fixflow.backend.common.api.PageResponse;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service=service; }
    @GetMapping public PageResponse<NotificationService.View> list(@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@RequestParam(defaultValue="false") boolean unread) { return service.list(page,size,unread); }
    @GetMapping("/unread-count") public Map<String,Long> count() { return Map.of("unreadCount",service.unreadCount()); }
    @PatchMapping("/{id}/read") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void read(@PathVariable @Positive long id) { service.read(id); }
    @PatchMapping("/read-all") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void all() { service.readAll(); }
}
