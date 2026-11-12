package com.openjiuwen.samples.creditmemory.agentmemory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * 基于 Java 11 {@link HttpClient} 的 {@link AgentMemoryHttp} 实现。
 *
 * <p>按方法区分超时：读写分离的路径（{@code /v1/search}、{@code /v1/list}）用 read timeout；
 * 写入（{@code /v1/add}、{@code /v1/update}、{@code /v1/evolve}、{@code /portal/api/launch}）
 * 用更长的 write timeout —— {@code infer=true} 的 add 会在服务端同步跑真实 LLM 抽取。</p>
 *
 * <p>始终带 {@code Origin: <baseUrl>}：{@code POST /portal/api/launch} 有同源校验，
 * 本机 Agent Memory 要求 Origin 与访问地址一致；其他接口附带该头无副作用。</p>
 */
public final class JdkAgentMemoryHttp implements AgentMemoryHttp {

    private static final Set<String> READ_ONLY_WRITE_PATHS = Set.of("/v1/search", "/v1/list", "/v1/get");

    private final AgentMemoryConfig config;
    private final HttpClient client;

    public JdkAgentMemoryHttp(AgentMemoryConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Response exchange(String method, String path, String jsonBody) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + path))
                .timeout(Duration.ofMillis(timeoutFor(path)))
                .header("Accept", "application/json")
                .header("Origin", config.baseUrl());
        if (config.hasAuthToken()) {
            builder.header("Authorization", "Bearer " + config.authToken());
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private int timeoutFor(String path) {
        return READ_ONLY_WRITE_PATHS.contains(path) ? config.readTimeoutMs() : config.writeTimeoutMs();
    }
}
