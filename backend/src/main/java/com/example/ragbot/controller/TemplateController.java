package com.example.ragbot.controller;

import com.example.ragbot.service.QaParsingService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final QaParsingService qaParsing;
    public TemplateController(QaParsingService qaParsing) { this.qaParsing = qaParsing; }

    @GetMapping("/qa")
    public ResponseEntity<byte[]> qaTemplate() {
        byte[] bytes = qaParsing.template().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=qa_template.csv")
                .body(bytes);
    }
}
