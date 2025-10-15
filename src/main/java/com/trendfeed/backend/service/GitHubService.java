package com.trendfeed.backend.service;

import com.trendfeed.backend.entity.GitHubEntity;
import com.trendfeed.backend.repository.GitHubRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    // ====== 주기 수집: 3시간마다 ======
    @Scheduled(cron = "${crawler.cron:0 0 */3 * * *}")
    public void crawlTopRepositories() {
        String languageFilter = buildLanguageFilter(languagesCsv);

        for (int page = 1; page <= maxPages; page++) {
            String q = baseQuery + languageFilter; // e.g., "stars:>5000+language:java+language:kotlin"

            Map<String, Object> searchResult = github.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search/repositories")
                            .queryParam("q", q)
                            .queryParam("sort", "stars")
                            .queryParam("order", "desc")
                            .queryParam("per_page", perPage)
                            .queryParam("page", page)
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
                    // 너무 빠른 폭탄요청 방지(초간단 간격)
                    Thread.sleep(120L);
                } catch (Exception ignore) {
                    // TODO: 로깅
                }
            }
        }
    }

    // ====== 단일 리포 업서트(메타 + README) ======
    public void upsertRepository(String fullName) {
        // 1) 메타데이터
        Map<String, Object> meta = github.get()
                .uri("/repos/{fullName}", fullName)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (meta == null) return;

        GitHubEntity e = mapToEntity(meta, repoRepo.findById(((Number) meta.get("id")).longValue()).orElse(null));

        // 2) README(조건부 요청)
        fetchAndAttachReadme(fullName, e);

        e.setLastCrawledAt(OffsetDateTime.now());
        repoRepo.save(e);
    }

    // ====== README 조건부 요청 + 디코딩 ======
    private void fetchAndAttachReadme(String fullName, GitHubEntity e) {
        ClientResponse response = github.get()
                .uri("/repos/{fullName}/readme", fullName)
                .headers(h -> {
                    if (e.getReadmeEtag() != null) {
                        h.add("If-None-Match", e.getReadmeEtag());
                    }
                })
                .exchange()
                .block();

        if (response == null) return;

        if (response.statusCode() == HttpStatus.NOT_MODIFIED) {
            // 304 → 변경 없음
            return;
        }

        if (response.statusCode().is2xxSuccessful()) {
            Map<String, Object> readme = response.bodyToMono(Map.class).block();
            if (readme == null) return;

            String encoded = (String) readme.get("content");
            String encoding = (String) readme.get("encoding"); // "base64" 기대
            String sha = (String) readme.get("sha");

            String text = null;
            if (encoded != null && "base64".equalsIgnoreCase(encoding)) {
                byte[] bytes = Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.UTF_8));
                text = new String(bytes, StandardCharsets.UTF_8);
            }

            e.setReadmeText(text);
            e.setReadmeSha(sha);
            // 응답 헤더의 ETag 저장
            String etag = response.headers().asHttpHeaders().getETag();
            e.setReadmeEtag(etag);
        }
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
