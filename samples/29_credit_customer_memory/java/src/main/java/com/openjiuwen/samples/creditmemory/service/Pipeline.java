package com.openjiuwen.samples.creditmemory.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆加工流水线的运行态（给页面轮询用）。
 */
public final class Pipeline {

    private Pipeline() {
    }

    public static final class Stage {
        public String key;
        public String label;
        public String detail = "";
        public String status = "pending";
        public long durationMs;

        public Stage() {
        }

        public Stage(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    public static final class Stats {
        public int llmCalls;
        public int memoriesAdded;
        public int memoriesUpdated;
        public int memoriesDeleted;
        public int graphEntitiesAdded;
        public int graphRelationsAdded;
        public int graphEpisodesAdded;
        public List<String> writeTargets = new ArrayList<>();
    }

    public static final class Outputs {
        public List<String> memories = new ArrayList<>();
        public List<String> graph = new ArrayList<>();
        public List<String> removed = new ArrayList<>();
    }

    public static final class Sample {
        public List<String> input = new ArrayList<>();
        public Outputs outputs = new Outputs();
    }

    public static final class Run {
        public String id;
        public String trigger = "对话后异步触发";
        public String at;
        public String status = "running";
        public String error;
        public Stats stats = new Stats();
        public Sample sample = new Sample();
        public List<Stage> stages = new ArrayList<>();

        public Run() {
        }

        public Run(String id, String at) {
            this.id = id;
            this.at = at;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("trigger", trigger);
            map.put("at", at);
            map.put("status", status);
            map.put("error", error);
            map.put("stats", stats);
            map.put("sample", sample);
            map.put("stages", stages);
            return map;
        }
    }
}
