package com.example.ragbot.controller;

import com.example.ragbot.dto.KbDtos;
import com.example.ragbot.security.CurrentUser;
import com.example.ragbot.service.KnowledgeBaseService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;
    private final CurrentUser currentUser;

    public KnowledgeBaseController(KnowledgeBaseService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<KbDtos.KbVO> list() { return service.list(currentUser.id()); }

    @PostMapping
    public KbDtos.KbVO create(@RequestBody KbDtos.KbRequest req) {
        return service.create(currentUser.get(), req);
    }

    @PutMapping("/{id}")
    public KbDtos.KbVO update(@PathVariable UUID id, @RequestBody KbDtos.KbRequest req) {
        return service.update(currentUser.id(), id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) { service.delete(currentUser.id(), id); }
}
