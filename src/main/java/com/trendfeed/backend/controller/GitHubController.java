package com.trendfeed.backend.controller;

import com.trendfeed.backend.service.GitHubService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/github")
public class GitHubController {

    private final GitHubService service;

    public GitHubController(GitHubService service) {
        this.service = service;
    }

    // 수동 트리거(옵션): GET /api/github/ingest?fullName=owner/repo
    @GetMapping("/ingest")
    public ResponseEntity<String> ingest(@RequestParam String fullName) {
        service.upsertRepository(fullName);
        return ResponseEntity.ok("ingested: " + fullName);
    }
}
