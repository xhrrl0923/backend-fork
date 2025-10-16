package com.trendfeed.backend.service;

import com.trendfeed.backend.entity.GitHubEntity;
import com.trendfeed.backend.repository.GitHubRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class GitHubService {

    private final WebClient github;
    private final GitHubRepository repoRepo;

    // ---- 수집 파라미터(프로퍼티로 조정) ----
    @Value("${crawler.query:stars:>5000}")
    private String baseQuery;

    @Value("${crawler.languages:}")  // 예: "java,kotlin,typescript"
    private String languagesCsv;

    @Value("${crawler.per-page:50}")
    private int perPage;

    @Value("${crawler.max-pages:3}")
    private int maxPages;

    public GitHubService(WebClient githubWebClient, GitHubRepository repoRepo) {
        this.github = githubWebClient;
        this.repoRepo = repoRepo;
    }

    // owner/repo 분리 유틸
    private static String[] splitFullName(String fullName) {
        if (fullName == null || !fullName.contains("/")) {
            throw new IllegalArgumentException("fullName must be like 'owner/repo'");
        }
        String[] parts = fullName.split("/", 2);
        return new String[]{ parts[0].trim(), parts[1].trim() };
    }

    // ====== 주기 수집: 3시간마다 ======
    @Scheduled(cron = "${crawler.cron:0 0 */3 * * *}")
    public void crawlTopRepositories() {
        String languageFilter = buildLanguageFilter(languagesCsv);

        for (int page = 1; page <= maxPages; page++) {
            final int currentPage = page;    // lambda용 복사본
            final int pageSize    = perPage; // lambda용 복사본

            String q = baseQuery + languageFilter; // e.g., "stars:>5000+language:java+language:kotlin"

            Map<String, Object> searchResult = github.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search/repositories")
                            .queryParam("q", q)
                            .queryParam("sort", "stars")
                            .queryParam("order", "desc")
                            .queryParam("per_page", pageSize)
                            .queryParam("page", currentPage)
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (searchResult == null) break;

            List<Map<String, Object>> items = (List<Map<String, Object>>) searchResult.get("items");
            if (items == null || items.isEmpty()) break;

            for (Map<String, Object> item : items) {
                String fullName = (String) item.get("full_name"); // "owner/repo"
                try {
                    upsertRepository(fullName);
                    Thread.sleep(120L); // 간단한 rate 조절
                } catch (Exception ignore) {
                    // TODO: 로깅
                }
            }
        }
    }

    // ====== 단일 리포 업서트(메타 + README) ======
    public void upsertRepository(String fullName) {
        // ✅ owner/repo 분리
        String[] parts = splitFullName(fullName);
        String owner = parts[0];
        String repo  = parts[1];

        // 1) 메타데이터 (✅ 경로를 /repos/{owner}/{repo} 로 변경)
        Map<String, Object> meta = github.get()
                .uri("/repos/{owner}/{repo}", owner, repo)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (meta == null) return;

        GitHubEntity e = mapToEntity(meta, repoRepo.findById(((Number) meta.get("id")).longValue()).orElse(null));

        // 2) README(조건부 요청) — ✅ owner/repo 인자로 변경
        fetchAndAttachReadme(owner, repo, e);

        e.setLastCrawledAt(OffsetDateTime.now());
        repoRepo.save(e);
    }

    // ====== README 조건부 요청 + 디코딩 ======
    private void fetchAndAttachReadme(String owner, String repo, GitHubEntity e) {
        ClientResponse response = github.get()
                .uri("/repos/{owner}/{repo}/readme", owner, repo)
                .headers(h -> {
                    if (e.getReadmeEtag() != null) {
                        h.add("If-None-Match", e.getReadmeEtag());
                    }
                })
                .exchange()
                .block();

        if (response == null) return;
        if (response.statusCode() == HttpStatus.NOT_MODIFIED) return;
        if (!response.statusCode().is2xxSuccessful()) return;

        Map<String, Object> readme = response.bodyToMono(Map.class).block();
        if (readme == null) return;

        String encoded  = (String) readme.get("content");
        String encoding = (String) readme.get("encoding"); // 보통 "base64"
        String sha      = (String) readme.get("sha");

        String text = null;
        if (encoded != null && "base64".equalsIgnoreCase(encoding)) {
            try {
                // ✅ 줄바꿈/공백 허용(MIME) 디코더
                byte[] bytes = java.util.Base64.getMimeDecoder().decode(encoded);
                text = new String(bytes, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                // 폴백 1: 공백 제거 후 표준 디코더
                String cleaned = encoded.replaceAll("\\s+", "");
                try {
                    byte[] bytes = java.util.Base64.getDecoder().decode(cleaned);
                    text = new String(bytes, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ex2) {
                    // 폴백 2: raw URL로 재시도 (download_url 있으면)
                    try {
                        String downloadUrl = (String) readme.get("download_url");
                        if (downloadUrl != null) {
                            text = github.get()
                                    .uri(downloadUrl)
                                    .header("Accept", "application/vnd.github.raw")
                                    .retrieve()
                                    .bodyToMono(String.class)
                                    .block();
                        }
                    } catch (Exception ignore) { /* README 없이 저장 */ }
                }
            }
        }

        e.setReadmeText(text);
        e.setReadmeSha(sha);
        String etag = response.headers().asHttpHeaders().getETag();
        e.setReadmeEtag(etag);
    }

    // ====== 메타 → 엔티티 매핑 ======
    @SuppressWarnings("unchecked")
    private GitHubEntity mapToEntity(Map<String, Object> meta, GitHubEntity existing) {
        GitHubEntity e = (existing != null) ? existing : new GitHubEntity();

        e.setId(((Number) meta.get("id")).longValue());
        e.setNodeId((String) meta.get("node_id"));
        e.setName((String) meta.get("name"));
        e.setFullName((String) meta.get("full_name"));

        Map<String, Object> owner = (Map<String, Object>) meta.get("owner");
        e.setOwnerLogin(owner != null ? (String) owner.get("login") : null);

        e.setHtmlUrl((String) meta.get("html_url"));
        e.setDescription((String) meta.get("description"));
        e.setLanguage((String) meta.get("language"));
        Number stars = (Number) meta.get("stargazers_count");
        e.setStargazersCount(stars == null ? null : stars.intValue());

        e.setCreatedAt(parseTime((String) meta.get("created_at")));
        e.setPushedAt(parseTime((String) meta.get("pushed_at")));
        e.setUpdatedAt(parseTime((String) meta.get("updated_at")));

        return e;
    }

    private OffsetDateTime parseTime(String iso) {
        return iso == null ? null : OffsetDateTime.parse(iso);
    }

    private String buildLanguageFilter(String csv) {
        if (csv == null || csv.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String lang : csv.split(",")) {
            String trimmed = lang.trim();
            if (!trimmed.isEmpty()) {
                sb.append("+language:").append(trimmed);
            }
        }
        return sb.toString();
    }
}
