package com.trendfeed.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "git_repositories")
@Getter @Setter
public class GitHubEntity {

    @Id
    private Long id;                   // GitHub repo id (PK)

    private String nodeId;
    private String name;
    private String fullName;

    private String ownerLogin;
    private String htmlUrl;

    @Column(length = 2000)
    private String description;

    private String language;
    private Integer stargazersCount;

    private OffsetDateTime createdAt;
    private OffsetDateTime pushedAt;
    private OffsetDateTime updatedAt;

    // README 전문(테스트용 한 테이블 보관)
    @Column(columnDefinition = "TEXT")
    private String readmeText;

    // 조건부 요청(변경 감지)용
    private String readmeSha;
    private String readmeEtag;

    private OffsetDateTime lastCrawledAt;
}
