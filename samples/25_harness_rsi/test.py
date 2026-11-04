"""样例 25：独立 unittest 验收；集成用例只调用真实模型。"""

import importlib.util
import json
import os
import tempfile
from datetime import UTC, datetime
from pathlib import Path
import unittest

import yaml

SAMPLE_DIR = Path(__file__).resolve().parent
SAMPLE = SAMPLE_DIR
SOURCE_REFS = SAMPLE_DIR / "harness/harness_refs.yaml"
SOURCE_PACKAGE = SAMPLE_DIR / "harness/credit_review"
HOLDOUT_DATA = SAMPLE_DIR / "data/holdout_cases.json"
OUTPUT_HEADINGS = (
    "【客户与申请】", "【事实核验】", "【还款能力】",
    "【风险与政策依据】", "【审查建议】", "【人工复核】",
)


def load_business():
    spec = importlib.util.spec_from_file_location("sample25_business", SAMPLE_DIR / "main.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


materialize_runtime_refs = load_business().materialize_runtime_refs


def load_mapping(path: str | Path) -> dict:
    """读取对象型 YAML；样例中的 state/report/refs 均采用此结构。"""
    target = Path(path).expanduser().resolve()
    if not target.is_file():
        raise ValueError(f"必需文件不存在: {target}")
    data = yaml.safe_load(target.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"YAML 顶层必须是对象: {target}")
    return data


def resolve_single_package(refs_path: str | Path) -> Path:
    """按官方 refs 约定取得唯一 solver 包目录。"""
    refs_file = Path(refs_path).expanduser().resolve()
    refs = load_mapping(refs_file).get("harness_refs")
    if not isinstance(refs, dict) or set(refs) != {"solver"}:
        raise ValueError(f"Single Harness 必须且只能包含 solver: {refs_file}")
    package = Path(str(refs["solver"])).expanduser()
    if not package.is_absolute():
        package = refs_file.parent / package
    package = package.resolve()
    if not package.is_dir():
        raise ValueError(f"solver Harness 包不存在: {package}")
    return package


def select_final_refs(report: dict, source_refs: str) -> str:
    """发布成功才选择 published；无提升时保留最佳/当前/源基线。"""
    status = str(report.get("publication_status") or "")
    published = str(report.get("published_harness_refs_path") or "")
    if status == "published":
        if not published:
            raise ValueError("publication_status=published 但 published refs 为空")
        return published
    if status.startswith("not_published"):
        if published:
            raise ValueError("未发布状态不得包含 published refs")
        return str(report.get("best_harness_refs_path")
                   or report.get("current_harness_refs_path") or source_refs)
    raise ValueError(f"未知发布状态: {status!r}")


def assert_prompt_skill_only(source_package: str | Path, final_package: str | Path) -> list[str]:
    """路径级禁改门禁；允许官方生成的空 Tool/Rail 注册表，但不允许其内容变化。"""
    source = Path(source_package).resolve()
    final = Path(final_package).resolve()
    source_files = {p.relative_to(source).as_posix(): p for p in source.rglob("*") if p.is_file()}
    final_files = {p.relative_to(final).as_posix(): p for p in final.rglob("*") if p.is_file()}
    changed = []
    for relative in sorted(set(source_files) | set(final_files)):
        before = source_files.get(relative)
        after = final_files.get(relative)
        before_bytes = before.read_bytes() if before else None
        after_bytes = after.read_bytes() if after else None
        if before_bytes == after_bytes:
            continue
        if relative.startswith(("prompt_sections/", "skills/")):
            changed.append(relative)
            continue
        if relative == "manifest.json":
            if before is None or after is None:
                raise ValueError("不得新增或删除 manifest.json")
            source_manifest = json.loads(before.read_text(encoding="utf-8"))
            final_manifest = json.loads(after.read_text(encoding="utf-8"))
            for payload in (source_manifest, final_manifest):
                payload.pop("prompt_sections", None)
                payload.pop("skills", None)
            for group in ("tools", "rails"):
                if final_manifest.get(group) not in (None, []):
                    raise ValueError(f"manifest.json 禁止修改 {group}")
                source_manifest.pop(group, None)
                final_manifest.pop(group, None)
            if source_manifest != final_manifest or final_manifest.get("config") not in (None, {}):
                raise ValueError("manifest.json 存在 Prompt/Skill 之外的变更")
            changed.append(relative)
            continue
        if relative in {"tools/tools.yaml", "rails/rails.yaml"}:
            group = relative.split("/", 1)[0]
            for path in (before, after):
                if path is None:
                    continue
                payload = yaml.safe_load(path.read_text(encoding="utf-8"))
                entries = payload.get(group) if isinstance(payload, dict) else payload
                if entries not in (None, []):
                    raise ValueError(f"禁改注册表不能为空以外的内容: {relative}")
            # 0.1.20 会在候选包生成空注册表；没有资源变化，不视为 Tool/Rail 演进。
            continue
        raise ValueError(f"检测到 Prompt/Skill 之外的禁改文件: {relative}")
    return changed


def validate_evaluation(eval_ref_path: str, expected_case_ids: set[str]) -> dict:
    """校验真实执行与 LLM 评审证据；业务低分可完成，执行/评审故障不可完成。"""
    eval_ref = load_mapping(eval_ref_path)
    cases = eval_ref.get("cases")
    if not isinstance(cases, list) or len(cases) != len(expected_case_ids):
        raise ValueError(f"评测案例数不完整: {eval_ref_path}")
    actual_ids = [str(case.get("case_id") or "") for case in cases]
    if set(actual_ids) != expected_case_ids or len(set(actual_ids)) != len(actual_ids):
        raise ValueError(f"评测 case_id 不匹配: {actual_ids} != {sorted(expected_case_ids)}")
    summary_path = Path(str(eval_ref.get("summary_path") or "")).expanduser().resolve()
    if not summary_path.is_file():
        raise ValueError(f"缺少评测 summary: {summary_path}")
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    if summary.get("total_cases") != len(expected_case_ids) or not isinstance(
            summary.get("average_score"), int | float):
        raise ValueError(f"评测 summary 不完整: {summary_path}")
    for case in cases:
        case_id = str(case["case_id"])
        if case.get("status") not in {"passed", "failed"}:
            raise ValueError(f"{case_id} 状态不可验收: {case.get('status')}")
        metadata = case.get("metadata") or {}
        if metadata.get("execution_status") != "passed":
            raise ValueError(f"{case_id} execution_status 不是 passed，不得当作业务低分")
        if str(metadata.get("evaluation_method") or "") != "llm_as_judge":
            raise ValueError(f"{case_id} 缺少真实 llm_as_judge 评审")
        if not isinstance(case.get("score"), int | float):
            raise ValueError(f"{case_id} 缺少连续分数")
        result_path = Path(str(case.get("result_path") or "")).expanduser().resolve()
        if not result_path.is_file():
            raise ValueError(f"{case_id} 缺少 result.json: {result_path}")
        result = json.loads(result_path.read_text(encoding="utf-8"))
        if result.get("execution_status") != "passed" or result.get("error"):
            raise ValueError(f"{case_id} 存在执行错误，不得进入业务归因")
        if (result.get("evaluation") or {}).get("method") != "llm_as_judge":
            raise ValueError(f"{case_id} result.json 缺少 llm_as_judge 证据")
    return summary


def validate_run_evidence(result, expected_case_ids: set[str], source_refs: str) -> tuple[dict, str]:
    """交叉检查官方 state/report、全量基线、候选门禁与发布证据。"""
    state = load_mapping(result.state_path)
    report = load_mapping(result.report_path)
    if state.get("status") != "completed" or report.get("status") != "completed":
        raise ValueError("RSI state/report 未达到 completed")
    consistency_keys = (
        "baseline_score", "best_score", "baseline_eval_ref_path", "best_eval_ref_path",
        "best_harness_refs_path", "current_harness_refs_path",
        "published_harness_refs_path", "publication_status", "candidate_gates",
    )
    for key in consistency_keys:
        if state.get(key) != report.get(key):
            raise ValueError(f"state/report 字段不一致: {key}")
    if not isinstance(report.get("baseline_score"), int | float) or not isinstance(
            report.get("best_score"), int | float):
        raise ValueError("基线或最佳分数缺失")
    if report.get("analysis_unavailable_count") or report.get("analysis_failures"):
        raise ValueError("存在分析/评审故障，不得把本次运行视为已验收闭环")
    validate_evaluation(report["baseline_eval_ref_path"], expected_case_ids)
    if report.get("best_eval_ref_path") != report.get("baseline_eval_ref_path"):
        validate_evaluation(report["best_eval_ref_path"], expected_case_ids)
    gates = report.get("candidate_gates") or []
    for gate in gates:
        if gate.get("status") in {"inconclusive", "skipped", "error"}:
            raise ValueError(f"候选门禁存在不可验收状态: {gate.get('status')}")
        candidate_refs = str(gate.get("candidate_harness_refs_path") or "")
        if candidate_refs:
            resolve_single_package(candidate_refs)
            assert_prompt_skill_only(SOURCE_PACKAGE, resolve_single_package(candidate_refs))
        if gate.get("accepted") or gate.get("status") == "accepted":
            for key in ("candidate_harness_refs_path", "candidate_eval_ref_path", "full_eval_ref_path"):
                if not Path(str(gate.get(key) or "")).is_file():
                    raise ValueError(f"accepted 候选缺少证据: {key}")
            if not (gate.get("improvement_proof") or {}).get("qualified"):
                raise ValueError("accepted 候选缺少 qualified 改进证明")
            validate_evaluation(gate["full_eval_ref_path"], expected_case_ids)
    final_refs = select_final_refs(report, source_refs)
    if not Path(final_refs).is_file():
        raise ValueError(f"最终 Harness refs 不存在: {final_refs}")
    if report["publication_status"] == "published":
        if not report.get("accepted_candidate_count") or report["best_score"] <= report["baseline_score"]:
            raise ValueError("published 必须有 accepted 候选且最佳分高于基线")
    return report, final_refs



MATERIAL_NAMES = (
    "case-intake",
    "judge-rubric",
    "failure-feedback",
    "tool-contract",
    "optimization-charter",
    "release-approval",
)


def read_yaml(path: Path):
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def nested_keys(value):
    if isinstance(value, dict):
        for key, item in value.items():
            yield str(key)
            yield from nested_keys(item)
    elif isinstance(value, list):
        for item in value:
            yield from nested_keys(item)


class HarnessRsiStaticContractTest(unittest.TestCase):
    def require_file(self, relative: str) -> Path:
        path = SAMPLE / relative
        self.assertTrue(path.is_file(), f"缺少样例文件: {path}")
        return path

    def test_static_assets_exist(self):
        required = [
            "config/model.yaml",
            "config/rsi.yaml",
            "harness/harness_refs.yaml",
            "harness/credit_review/manifest.json",
            "harness/credit_review/prompt_sections/credit_review.md",
            "harness/credit_review/skills/pre_loan_review/SKILL.md",
            "data/optimization_cases.json",
            "data/holdout_cases.json",
        ]
        extensions = {"case-intake": "json", "judge-rubric": "json",
                      "failure-feedback": "md", "tool-contract": "yaml",
                      "optimization-charter": "yaml", "release-approval": "md"}
        for name in MATERIAL_NAMES:
            extension = extensions[name]
            required.append(f"materials/{name}.template.{extension}")
            required.append(f"materials/{name}.example.{extension}")
        for relative in required:
            self.require_file(relative)

    def test_model_config_contains_only_environment_credentials(self):
        path = self.require_file("config/model.yaml")
        config = read_yaml(path)
        client = config["model"]["model_client_config"]
        request = config["model"]["model_request_config"]
        self.assertEqual("${API_KEY}", client["api_key"])
        self.assertEqual("${API_BASE}", client["api_base"])
        self.assertEqual("${MODEL_NAME}", request["model"])
        self.assertNotIn("sk-", path.read_text(encoding="utf-8"))

    def test_rsi_config_has_real_judge_full_replay_and_small_budget(self):
        config = read_yaml(self.require_file("config/rsi.yaml"))
        self.assertEqual(1, config["max_epochs"])
        self.assertEqual(["prompt", "skill"], config["member_optimizer"]["action_group_configs"])
        self.assertEqual(1, config["member_optimizer"]["max_roles_per_run"])
        self.assertEqual("single_harness", config["evaluator"]["backend"])
        self.assertEqual("llm_as_judge", config["evaluator"]["evaluation_method"])
        self.assertEqual("continuous", config["evaluator"]["score_mode"])
        self.assertTrue(config["scheduling"]["full_evaluation_enabled"])
        self.assertEqual("continuous_two_stage_v2", config["promotion"]["policy_version"])
        self.assertGreater(config["promotion"]["min_improvement"], 0)
        self.assertLessEqual(config["data_loader"]["batch_size"], 2)
        self.assertLessEqual(config["evaluator"]["solver_max_iterations"], 12)
        self.assertLessEqual(config["evaluator"]["judge_agent_max_iterations"], 12)

    def test_harness_exposes_only_prompt_and_skill_resources(self):
        refs = read_yaml(self.require_file("harness/harness_refs.yaml"))["harness_refs"]
        self.assertEqual({"solver"}, set(refs))
        package = (SAMPLE / "harness" / refs["solver"]).resolve()
        self.assertTrue(package.is_relative_to(SAMPLE.resolve()))
        manifest_path = self.require_file("harness/credit_review/manifest.json")
        manifest = read_json(manifest_path)
        self.assertEqual("plugin", manifest["package_type"])
        self.assertFalse({"tools", "rails", "config", "mcps"} & set(manifest))
        self.assertEqual(1, len(manifest["prompt_sections"]))
        self.assertEqual(1, len(manifest["skills"]))
        prompt = manifest["prompt_sections"][0]
        self.assertTrue(prompt["file"].startswith("prompt_sections/"))
        self.assertTrue((package / prompt["file"]).is_file())
        self.assertTrue((package / manifest["skills"][0]["dir"] / "SKILL.md").is_file())

    def test_datasets_are_unique_scored_and_holdout_is_disjoint(self):
        datasets = {}
        for split in ("optimization", "holdout"):
            data = read_json(self.require_file(f"data/{split}_cases.json"))
            cases = data["cases"]
            self.assertGreaterEqual(len(cases), 2)
            ids = [case["case_id"] for case in cases]
            self.assertEqual(len(ids), len(set(ids)))
            datasets[split] = set(ids)
            for case in cases:
                self.assertIsInstance(case["input"], str)
                self.assertTrue(case["input"].strip())
                self.assertIs(case["synthetic"], True)
                self.assertIs(case["deidentified"], True)
                reference = case["reference"]
                self.assertTrue(reference["answer"])
                self.assertTrue(reference["policy_version"])
                behaviors = reference["required_behaviors"]
                self.assertGreaterEqual(len(behaviors), 2)
                self.assertAlmostEqual(1.0, sum(item["weight"] for item in behaviors))
                self.assertTrue(all(item["description"] for item in behaviors))
                self.assertTrue(reference["forbidden_behaviors"])
        self.assertFalse(datasets["optimization"] & datasets["holdout"])

    def test_materials_have_owners_evidence_and_signoff(self):
        for name in MATERIAL_NAMES:
            files = sorted((SAMPLE / "materials").glob(f"{name}.*"))
            self.assertEqual(2, len(files), f"{name} 必须同时有模板和完整示例")
            for path in files:
                if path.suffix == ".json":
                    content = read_yaml(path)
                elif path.suffix == ".yaml":
                    content = read_yaml(path)
                else:
                    content = path.read_text(encoding="utf-8")
                if isinstance(content, str):
                    for marker in ("责任人", "输入", "输出", "签收", "证据"):
                        self.assertIn(marker, content, f"{path.name} 缺少 {marker}")
                else:
                    keys = set(nested_keys(content))
                    for key in ("owner", "inputs", "outputs", "sign_off", "evidence"):
                        self.assertIn(key, keys, f"{path.name} 缺少 {key}")
            self.assertNotEqual(files[0].read_text(encoding="utf-8"),
                                  files[1].read_text(encoding="utf-8"))



class HarnessRsiContractTest(unittest.TestCase):
    def test_run_preparation_is_independent_of_cwd_and_does_not_load_holdout(self):
        prepare = load_business().prepare_run
        with tempfile.TemporaryDirectory() as temporary:
            original_cwd = Path.cwd()
            try:
                os.chdir(temporary)
                config, request = prepare("contract-run", runs_dir=Path(temporary))
            finally:
                os.chdir(original_cwd)
            self.assertEqual([str(SAMPLE / "data/optimization_cases.json")], request.dataset_files)
            self.assertEqual("contract-run", request.task_id)
            self.assertFalse(request.resume)
            self.assertEqual(str(Path(temporary).resolve() / "contract-run"), request.output_dir)
            self.assertEqual("single_harness", config.evaluator.backend)
            self.assertTrue(Path(config.model_configs.evaluation).is_absolute())
            refs = read_yaml(Path(request.harness_refs_path))
            self.assertEqual(str(SAMPLE / "harness/credit_review"), refs["harness_refs"]["solver"])
            with self.assertRaises(FileExistsError):
                prepare("contract-run", runs_dir=Path(temporary))
            Path(request.output_dir, "single_harness_state.yaml").write_text("status: running\n", encoding="utf-8")
            _, resumed = prepare("contract-run", resume=True, runs_dir=Path(temporary))
            self.assertTrue(resumed.resume)

    def make_package(self, root: Path) -> Path:
        package = root / "credit_review"
        (package / "prompt_sections").mkdir(parents=True)
        (package / "skills" / "pre_loan_review").mkdir(parents=True)
        (package / "manifest.json").write_text(json.dumps({
            "package_type": "plugin", "id": "credit_review",
            "prompt_sections": [{"file": "prompt_sections/credit_review.md"}],
            "skills": [{"dir": "./skills/pre_loan_review", "mode": "all"}],
            "metadata": {"policy_version": "DEMO-CREDIT-2026.1"},
        }), encoding="utf-8")
        (package / "prompt_sections" / "credit_review.md").write_text("原提示", encoding="utf-8")
        (package / "skills" / "pre_loan_review" / "SKILL.md").write_text("原技能", encoding="utf-8")
        return package

    def write_evaluation(self, root: Path, case_ids=("case-a",), execution_status="passed"):
        root.mkdir(parents=True, exist_ok=True)
        cases = []
        for case_id in case_ids:
            result_path = root / "cases" / case_id / "result.json"
            result_path.parent.mkdir(parents=True)
            result_path.write_text(json.dumps({
                "case_id": case_id, "status": "failed", "execution_status": execution_status,
                "score": 0.5, "evaluation": {"method": "llm_as_judge"},
                "result": {"output": "【客户与申请】\n【事实核验】\n【还款能力】\n【风险与政策依据】\n【审查建议】\n【人工复核】"},
                "error": "" if execution_status == "passed" else "connection failed",
            }), encoding="utf-8")
            cases.append({"case_id": case_id, "status": "failed", "score": 0.5,
                          "result_path": str(result_path),
                          "metadata": {"execution_status": execution_status,
                                       "evaluation_method": "llm_as_judge"}})
        summary_path = root / "summary.json"
        summary_path.write_text(json.dumps({"total_cases": len(case_ids), "average_score": 0.5}),
                                encoding="utf-8")
        eval_ref = root / "eval_ref.yaml"
        eval_ref.write_text(yaml.safe_dump({"summary_path": str(summary_path), "cases": cases}),
                            encoding="utf-8")
        return eval_ref

    def test_runtime_refs_use_absolute_package_path_without_mutating_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary) / "run"
            run_dir.mkdir()
            original = SOURCE_REFS.read_bytes()
            runtime_refs = materialize_runtime_refs(SOURCE_REFS, run_dir)
            ref = read_yaml(runtime_refs)["harness_refs"]["solver"]
            self.assertTrue(Path(ref).is_absolute())
            self.assertEqual(SOURCE_PACKAGE, resolve_single_package(runtime_refs))
            self.assertEqual(original, SOURCE_REFS.read_bytes())

    def test_selects_published_or_retained_baseline(self):
        self.assertEqual("published.yaml", select_final_refs({
            "publication_status": "published", "published_harness_refs_path": "published.yaml",
            "best_harness_refs_path": "best.yaml",
        }, "source.yaml"))
        self.assertEqual("best.yaml", select_final_refs({
            "publication_status": "not_published_no_improvement",
            "published_harness_refs_path": "", "best_harness_refs_path": "best.yaml",
        }, "source.yaml"))
        with self.assertRaisesRegex(ValueError, "published"):
            select_final_refs({"publication_status": "published",
                                    "published_harness_refs_path": ""}, "source.yaml")

    def test_allows_prompt_skill_changes_and_empty_generated_registries(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = self.make_package(Path(temporary) / "source")
            final = self.make_package(Path(temporary) / "final")
            (final / "prompt_sections" / "credit_review.md").write_text("新提示", encoding="utf-8")
            (final / "skills" / "pre_loan_review" / "SKILL.md").write_text("新技能", encoding="utf-8")
            for group in ("tools", "rails"):
                (final / group).mkdir()
                (final / group / f"{group}.yaml").write_text(f"{group}: []\n", encoding="utf-8")
            manifest = read_json(final / "manifest.json")
            manifest.update(tools=[], rails=[])
            (final / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            changed = assert_prompt_skill_only(source, final)
            self.assertIn("prompt_sections/credit_review.md", changed)
            self.assertIn("skills/pre_loan_review/SKILL.md", changed)

    def test_rejects_non_prompt_skill_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = self.make_package(Path(temporary) / "source")
            final = self.make_package(Path(temporary) / "final")
            (final / "tools").mkdir()
            (final / "tools" / "credit.py").write_text("raise RuntimeError", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "tools/credit.py"):
                assert_prompt_skill_only(source, final)

    def test_evaluation_requires_complete_real_execution_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            valid = self.write_evaluation(root / "valid", ("a", "b"))
            summary = validate_evaluation(str(valid), {"a", "b"})
            self.assertEqual(0.5, summary["average_score"])
            invalid = self.write_evaluation(root / "invalid", ("a",), execution_status="failed")
            with self.assertRaisesRegex(ValueError, "execution_status"):
                validate_evaluation(str(invalid), {"a"})

    def test_no_improvement_run_is_completed_but_not_published(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_refs = root / "source.yaml"
            source_refs.write_text("harness_refs: {solver: credit_review}\n", encoding="utf-8")
            baseline = self.write_evaluation(root / "baseline", ("a", "b"))
            state_path = root / "single_harness_state.yaml"
            report_path = root / "single_harness_report.yaml"
            shared = {
                "status": "completed", "baseline_score": 0.5, "best_score": 0.5,
                "baseline_eval_ref_path": str(baseline), "best_eval_ref_path": str(baseline),
                "best_harness_refs_path": str(source_refs), "current_harness_refs_path": str(source_refs),
                "published_harness_refs_path": "", "publication_status": "not_published_no_improvement",
                "candidate_gates": [], "accepted_candidate_count": 0,
            }
            state_path.write_text(yaml.safe_dump(shared), encoding="utf-8")
            report_path.write_text(yaml.safe_dump(shared), encoding="utf-8")
            result = type("Result", (), {"state_path": str(state_path), "report_path": str(report_path)})()
            report, final_refs = validate_run_evidence(result, {"a", "b"}, str(source_refs))
            self.assertEqual("completed", report["status"])
            self.assertEqual(str(source_refs), final_refs)



class HarnessRsiRealModelTest(unittest.IsolatedAsyncioTestCase):
    async def test_real_rsi_reload_smoke_and_independent_holdout(self):
        import openjiuwen
        from openjiuwen.harness.resources import find_plugin_manifest, load_plugin_package
        from openjiuwen.rsi import TeamEvaluator, load_cases

        self.assertEqual("0.1.20", openjiuwen.__version__)
        for variable in ("API_BASE", "API_KEY", "MODEL_NAME"):
            self.assertTrue(os.getenv(variable, "").strip(), f"真实模型验收需要 {variable}")
        business = load_business()
        cases = load_cases([str(business.OPTIMIZATION_DATA)])
        holdout = load_cases([str(HOLDOUT_DATA)])
        case_ids = {case["case_id"] for case in cases}
        holdout_ids = {case["case_id"] for case in holdout}
        self.assertFalse(case_ids & holdout_ids)
        task_id = datetime.now(UTC).strftime("credit-rsi-ut-%Y%m%dT%H%M%S%fZ")
        run_dir = SAMPLE_DIR / "runs" / task_id
        print(f"真实模型验收目录：{run_dir}", flush=True)
        result = await business.run(task_id)
        source_refs = str(run_dir / "source_harness_refs.yaml")
        report, final_refs = validate_run_evidence(result, case_ids, source_refs)
        package = resolve_single_package(final_refs)
        changed = assert_prompt_skill_only(SOURCE_PACKAGE, package)
        plugin = load_plugin_package(find_plugin_manifest(package))
        self.assertTrue(plugin.prompt_sections)
        self.assertTrue(plugin.skills)
        config, _ = business.prepare_run(task_id, resume=True)
        scores = {}
        for name, refs in (("baseline", source_refs), ("final", final_refs)):
            evaluation = await TeamEvaluator(config.evaluator).evaluate_batch(
                cases=holdout, team_skill_ref_path="", harness_refs_path=refs,
                output_dir=str(run_dir / "holdout" / name), case_concurrency=1,
            )
            scores[name] = validate_evaluation(evaluation, holdout_ids)["average_score"]
            if name == "final":
                for case in load_mapping(evaluation)["cases"]:
                    output = json.loads(Path(case["result_path"]).read_text(encoding="utf-8"))["result"]
                    text = json.dumps(output, ensure_ascii=False)
                    for heading in OUTPUT_HEADINGS:
                        self.assertIn(heading, text, f"{case['case_id']} 缺少贷前审查结构")
        if os.getenv("REQUIRE_IMPROVEMENT") == "1":
            self.assertEqual("published", report["publication_status"])
            self.assertGreater(report["best_score"], report["baseline_score"])
        print(json.dumps({
            "task_id": task_id, "report": result.report_path,
            "publication_status": report["publication_status"],
            "score_scale": 100,
            "baseline_score": round(report["baseline_score"] * 100, 2),
            "best_score": round(report["best_score"] * 100, 2),
            "changed_prompt_skill_files": changed,
            "holdout_scores": {name: round(score * 100, 2) for name, score in scores.items()},
        }, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
