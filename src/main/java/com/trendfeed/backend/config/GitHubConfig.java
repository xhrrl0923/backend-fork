package com.trendfeed.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class GitHubConfig {

    @Bean
    public WebClient githubWebClient(
            @Value("${github.base-url}") String baseUrl,
            @Value("${github.token}") String token
    ) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(30));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("User-Agent", "trendfeed-crawler")
                // 간단 로깅 필터
                .filter(ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
                    // 필요 시 상태 코드 로깅 등 추가
                    return reactor.core.publisher.Mono.just(clientResponse);
                }))
                .build();
    }
}
