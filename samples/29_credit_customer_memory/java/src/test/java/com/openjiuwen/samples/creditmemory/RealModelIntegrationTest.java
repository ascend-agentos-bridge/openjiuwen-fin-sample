package com.openjiuwen.samples.creditmemory;

import com.openjiuwen.samples.creditmemory.config.ModelSettings;
import com.openjiuwen.samples.creditmemory.service.ChatService;
import com.openjiuwen.samples.creditmemory.service.MemoryService;
import com.openjiuwen.samples.creditmemory.service.Pipeline;
import com.openjiuwen.samples.creditmemory.service.SessionStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实模型验收（需可用的 OpenAI 兼容服务）：
 * mvn -Preal-model -Dtest=RealModelIntegrationTest test
 */
@Tag("integration")
class RealModelIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void chatAndLayeredMemoryWithRealModel() throws Exception {
        ModelSettings settings = ModelSettings.load(tempDir.resolve("missing.json"));
        assertTrue(settings.isConfigured());

        ChatService chatService = new ChatService(settings);
        SessionStore.Message customer = new SessionStore.Message("m1", "customer",
                "你好，我是周砚清，在杭州做精密制造，想申请80万经营贷，分36期。", "10:00");
        String reply = chatService.answer(List.of(customer), new MemoryService.RecallResult("你好"));
        assertNotNull(reply);
        assertFalse(reply.isBlank());

        MemoryService memoryService = new MemoryService(settings, tempDir.resolve("data"));
        try {
            assertNull(memoryService.initError(), "记忆引擎初始化失败");
            SessionStore.Session session = new SessionStore.Session();
            session.id = "it-session-001";
            session.title = "集成测试";
            session.createdAt = "2026-11-12 10:00";
            AtomicReference<Pipeline.Run> finished = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            Pipeline.Run run = memoryService.startProcessing(session, customer.text, reply,
                    List.of("客户：" + customer.text, "智能体：" + reply), result -> {
                        finished.set(result);
                        latch.countDown();
                    });
            assertTrue(latch.await(15, TimeUnit.MINUTES), "记忆加工超时");
            assertNotNull(finished.get());
            assertEqualsStatus("done", finished.get());
            assertTrue(finished.get().stats.memoriesAdded > 0, "应抽取出长期记忆");
            assertFalse(memoryService.memoryView().get("memories").toString().equals("[]"), "记忆列表不应为空");
            assertFalse(memoryService.graphView().get("entities").toString().equals("[]"), "图实体不应为空");
            assertNotNull(memoryService.recall("周砚清 的还款偏好"));
        } finally {
            memoryService.shutdown();
        }
    }

    private static void assertEqualsStatus(String expected, Pipeline.Run run) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, run.status, run.error);
    }
}
