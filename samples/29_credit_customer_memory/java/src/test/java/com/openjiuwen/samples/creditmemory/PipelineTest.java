package com.openjiuwen.samples.creditmemory;

import com.openjiuwen.samples.creditmemory.service.Pipeline;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineTest {

    @Test
    void runSerializesStagesAndSample() {
        Pipeline.Run run = new Pipeline.Run("run-1", "10:20:00");
        Pipeline.Stage stage = new Pipeline.Stage("analyze", "LLM 记忆分析");
        stage.status = "done";
        run.stages.add(stage);
        run.sample.input.add("客户：你好");
        run.sample.outputs.memories.add("用户想要经营贷");
        run.stats.memoriesAdded = 1;
        Map<String, Object> serialized = run.toMap();
        assertEquals("run-1", serialized.get("id"));
        assertEquals("running", serialized.get("status"));
        assertTrue(serialized.containsKey("stages"));
        Pipeline.Sample sample = (Pipeline.Sample) serialized.get("sample");
        assertTrue(sample.input.contains("客户：你好"));
    }
}
