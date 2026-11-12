package com.openjiuwen.samples.creditmemory.agentmemory;

/**
 * Agent Memory REST 调用失败。
 *
 * <p>保留服务端错误信封里的字段（error / message / request_id / retryable），
 * 便于调用方区分「业务拒绝」（4xx，重试无用）与「服务不可用」（5xx/网络，可重试）。
 * 异常信息只包含 HTTP 状态与受控文案，不包含请求头或密钥。</p>
 */
public final class AgentMemoryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** HTTP 状态码；网络/解析失败时为 -1。 */
    private final int statusCode;
    /** 服务端错误信封中的 error 类名；无响应体时为空串。 */
    private final String errorClass;
    /** 服务端生成的 request_id，便于对照服务端日志；无响应体时为空串。 */
    private final String requestId;
    /** 服务端提示是否可重试（429/503）；网络失败按可重试处理。 */
    private final boolean retryable;

    public AgentMemoryException(String message, int statusCode, String errorClass,
                                String requestId, boolean retryable, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorClass = errorClass == null ? "" : errorClass;
        this.requestId = requestId == null ? "" : requestId;
        this.retryable = retryable;
    }

    public int statusCode() {
        return statusCode;
    }

    public String errorClass() {
        return errorClass;
    }

    public String requestId() {
        return requestId;
    }

    public boolean retryable() {
        return retryable;
    }

    /** HTTP 非 2xx。 */
    public static AgentMemoryException http(String operation, int status, String errorClass,
                                            String serverMessage, String requestId, boolean retryable) {
        String detail = errorClass.isBlank() ? "HTTP " + status : errorClass + " (HTTP " + status + ")";
        String message = "Agent Memory " + operation + " 失败: " + detail
                + (serverMessage == null || serverMessage.isBlank() ? "" : " - " + serverMessage)
                + (requestId == null || requestId.isBlank() ? "" : " [request_id=" + requestId + "]");
        return new AgentMemoryException(message, status, errorClass, requestId, retryable, null);
    }

    /** 网络/解析等传输层失败。 */
    public static AgentMemoryException transport(String operation, Throwable cause) {
        return new AgentMemoryException("Agent Memory " + operation + " 传输失败: " + cause.getMessage(),
                -1, "", "", true, cause);
    }

    /** 响应体不符合约定（缺字段/类型不符）。 */
    public static AgentMemoryException protocol(String operation, String detail, String responseBody) {
        String snippet = responseBody == null ? "" : responseBody.strip();
        if (snippet.length() > 300) {
            snippet = snippet.substring(0, 300) + "...";
        }
        return new AgentMemoryException("Agent Memory " + operation + " 响应不符合约定: " + detail
                + (snippet.isBlank() ? "" : "，body=" + snippet), -1, "", "", false, null);
    }
}
