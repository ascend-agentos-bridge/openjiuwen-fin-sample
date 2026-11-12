package com.openjiuwen.samples.creditmemory.agentmemory;

import java.io.IOException;

/**
 * Agent Memory HTTP 传输层的最小抽象。
 *
 * <p>生产实现是 {@link JdkAgentMemoryHttp}（Java 11 HttpClient）。抽出这一层只有一个目的：
 * 让 {@link AgentMemoryClient} / {@link AgentMemoryProvider} 的「请求构造 + 响应解析」可以在
 * 默认 JUnit 测试里离线断言（记录实际发出的 path/body），不依赖本机 Agent Memory 服务。</p>
 *
 * <p>注意：它不是对 openjiuwen 框架能力的封装，也不替代 {@code MemoryProvider}；
 * provider 本身仍直接实现框架的 {@code com.openjiuwen.core.memory.external.MemoryProvider}。</p>
 */
public interface AgentMemoryHttp {

    /** 一次 HTTP 调用的原始结果。 */
    record Response(int status, String body) {
    }

    /**
     * 发起一次同步 HTTP 请求。
     *
     * @param method   GET / POST
     * @param path     以 / 开头的路径，例如 /v1/search
     * @param jsonBody POST 请求体；GET 传 null
     * @return 状态码与响应体
     * @throws IOException         网络层失败
     * @throws InterruptedException 调用线程被中断
     */
    Response exchange(String method, String path, String jsonBody) throws IOException, InterruptedException;
}
