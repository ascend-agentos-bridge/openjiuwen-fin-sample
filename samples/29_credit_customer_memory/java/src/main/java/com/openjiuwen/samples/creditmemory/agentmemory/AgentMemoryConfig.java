package com.openjiuwen.samples.creditmemory.agentmemory;

/**
 * Agent Memory 服务连接参数与 dev auth 身份。
 *
 * <p>全部可由环境变量覆盖，代码里不写死密钥：</p>
 *
 * <ul>
 *   <li>{@code AGENT_MEMORY_BASE_URL}，缺省 {@code http://127.0.0.1:8137}；</li>
 *   <li>{@code AGENT_MEMORY_ORG} / {@code AGENT_MEMORY_USER}：认证身份，dev 缺省 {@code local/developer}；</li>
 *   <li>{@code AGENT_MEMORY_TOKEN}：可选 Bearer Token（dev auth 下不需要）；</li>
 *   <li>{@code AGENT_MEMORY_READ_TIMEOUT_MS} / {@code AGENT_MEMORY_WRITE_TIMEOUT_MS}：
 *       检索默认 30s，写入默认 180s（{@code infer=true} 的 /v1/add 会同步调用真实 LLM 抽取）。</li>
 * </ul>
 */
public final class AgentMemoryConfig {

    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8137";
    public static final String DEFAULT_ORG = "local";
    public static final String DEFAULT_USER = "developer";

    private final String baseUrl;
    private final String identityOrg;
    private final String identityUser;
    private final String authToken;
    private final int readTimeoutMs;
    private final int writeTimeoutMs;

    public AgentMemoryConfig(String baseUrl, String identityOrg, String identityUser,
                             String authToken, int readTimeoutMs, int writeTimeoutMs) {
        this.baseUrl = stripTrailingSlash(requireNonBlank(baseUrl, "baseUrl"));
        this.identityOrg = requireNonBlank(identityOrg, "identityOrg");
        this.identityUser = requireNonBlank(identityUser, "identityUser");
        this.authToken = authToken == null ? "" : authToken.strip();
        if (readTimeoutMs <= 0 || writeTimeoutMs <= 0) {
            throw new IllegalArgumentException("timeouts must be positive");
        }
        this.readTimeoutMs = readTimeoutMs;
        this.writeTimeoutMs = writeTimeoutMs;
    }

    /** 从环境变量读取（缺省值见类注释）。 */
    public static AgentMemoryConfig fromEnvironment() {
        return new AgentMemoryConfig(
                env("AGENT_MEMORY_BASE_URL", DEFAULT_BASE_URL),
                env("AGENT_MEMORY_ORG", DEFAULT_ORG),
                env("AGENT_MEMORY_USER", DEFAULT_USER),
                env("AGENT_MEMORY_TOKEN", ""),
                intEnv("AGENT_MEMORY_READ_TIMEOUT_MS", 30_000),
                intEnv("AGENT_MEMORY_WRITE_TIMEOUT_MS", 180_000));
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String identityOrg() {
        return identityOrg;
    }

    public String identityUser() {
        return identityUser;
    }

    public String authToken() {
        return authToken;
    }

    public boolean hasAuthToken() {
        return !authToken.isBlank();
    }

    public int readTimeoutMs() {
        return readTimeoutMs;
    }

    public int writeTimeoutMs() {
        return writeTimeoutMs;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.strip();
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
