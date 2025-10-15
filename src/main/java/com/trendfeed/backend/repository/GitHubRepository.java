package main.java.com.trendfeed.backend.repository;

import com.trendfeed.backend.entity.GitHubEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubRepository extends JpaRepository<GitHubEntity, Long> {
    GitHubEntity findByFullName(String fullName);
}
