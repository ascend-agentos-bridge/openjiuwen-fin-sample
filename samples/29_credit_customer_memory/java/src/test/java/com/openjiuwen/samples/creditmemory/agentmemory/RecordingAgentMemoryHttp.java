package com.openjiuwen.samples.creditmemory.agentmemory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 离线测试替身：记录客户端实际发出的 HTTP 请求，按入队顺序返回预置响应。
 *
 * <p>仅用于规则单测验证「请求构造 + 响应解析」，不参与真实运行。</p>
 */
final class RecordingAgentMemoryHttp implements AgentMemoryHttp {

    record Call(String method, String path, String body) {
    }

    private final List<Call> calls = new ArrayList<>();
    private final Deque<Response> responses = new ArrayDeque<>();
    private final List<IOException> failures = new ArrayList<>();

    RecordingAgentMemoryHttp enqueue(int status, String body) {
        responses.addLast(new Response(status, body));
        return this;
    }

    RecordingAgentMemoryHttp failNextWith(IOException exception) {
        failures.add(exception);
        return this;
    }

    @Override
    public Response exchange(String method, String path, String jsonBody) throws IOException {
        calls.add(new Call(method, path, jsonBody));
        if (!failures.isEmpty()) {
            throw failures.remove(0);
        }
        Response response = responses.pollFirst();
        return response == null ? new Response(200, "{}") : response;
    }

    List<Call> calls() {
        return calls;
    }

    Call call(int index) {
        return calls.get(index);
    }

    Call lastCall() {
        return calls.get(calls.size() - 1);
    }

    int callCount() {
        return calls.size();
    }
}
