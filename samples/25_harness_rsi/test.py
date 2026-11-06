"""样例 25：独立 unittest 验收；业务入口只负责运行，断言全部在本文件。

离线用例覆盖：
- h0 Harness 的业界标准 Skill 目录（SKILL.md + references/ + scripts/）与官方加载；
- 冻结评测集（优化集 6 案 / 留出集 4 案）的结构与业务契约；
- RSI 配置（单批 6 案、max_epochs=1、Prompt/Skill 优化面、绝对 rubric 裁判、100 分制展示）；
- 运行证据验收规则（全量回放、发布门槛、挑战案实质提升、保护案不退化、Skill 目录完整）。

真实模型用例要求 API_BASE / API_KEY / MODEL_NAME，缺少时明确 skip；
REQUIRE_IMPROVEMENT=1 时额外要求官方 published 且最佳分高于基线。
"""

import ast
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
from datetime import UTC, datetime
from pathlib import Path
import unittest

import yaml

SAMPLE_DIR = Path(__file__).resolve().parent
SAMPLE = SAMPLE_DIR
SOURCE_REFS = SAMPLE_DIR / "harness/harness_refs.yaml"
SOURCE_PACKAGE = SAMPLE_DIR / "harness/credit_review"
SKILL_DIR_NAME = "pre_loan_review"
SKILL_DIR = SOURCE_PACKAGE / "skills" / SKILL_DIR_NAME
OPTIMIZATION_DATA = SAMPLE_DIR / "data/optimization_cases.json"
HOLDOUT_DATA = SAMPLE_DIR / "data/holdout_cases.json"
OUTPUT_HEADINGS = (
    "【客户与申请】", "【事实核验】", "【还款能力】",
    "【风险与政策依据】", "【审查建议】", "【人工复核】",
)

SKILL_REQUIRED_FILES = (
    "SKILL.md",
    "references/policy-demo.md",
    "references/output-format.md",
    "scripts/dti.py",
)
FORBIDDEN_MANIFEST_KEYS = ("tools", "rails", "config", "mcps")
DTI_DEFAULT_ARGS = ("--income", "20000", "--existing-debt", "3000", "--new-payment", "6000")
DTI_BOUNDARY_ARGS = ("--income", "25000", "--existing-debt", "7000", "--new-payment", "5501")

# 冻结协议：优化集 2 个保护案 + 4 个挑战案；留出集 4 案只在 test.py 使用。
PROTECTED_CASE_IDS = frozenset({"opt-current-overdue", "opt-eligible-review"})
CHALLENGE_CASE_IDS = frozenset({
    "opt-mixed-periods", "opt-duplicate-debt",
    "opt-duplicate-transfer", "opt-superseded-record",
})
OPTIMIZATION_CASE_IDS = PROTECTED_CASE_IDS | CHALLENGE_CASE_IDS
HOLDOUT_CASE_IDS = frozenset({
    "hold-mixed-periods", "hold-duplicate-debt",
    "hold-superseded-record", "hold-precision-boundary",
})

# 发布后的业务验收门槛：挑战案至少 2 个相对基线实质提升，保护案不退化。
CHALLENGE_IMPROVEMENT_THRESHOLD = 0.2
CHALLENGE_IMPROVEMENT_REQUIRED = 2
PROTECTED_REGRESSION_TOLERANCE = 0.05
EPSILON = 1e-9

# 冻结案例契约（与 .cache/s25dev/spec.md 第 3 节一致）。
CASE_CONTRACTS = {
    "opt-current-overdue": {
        "dimension": "credit_history",
        "required": {"ratio": 0.25, "overdue": 0.25, "recommendation": 0.25, "structure": 0.25},
        "forbidden": {"direct_approval": 1.0, "invented_clearance": 0.5},
        "answer_markers": ("45%", "逾期"),
        "input_markers": ("3000", "6000"),
    },
    "opt-eligible-review": {
        "dimension": "normal_review",
        "required": {"ratio": 0.3, "facts": 0.2, "recommendation": 0.3, "structure": 0.2},
        "forbidden": {"invented_risk": 0.5, "final_approval": 1.0},
        "answer_markers": ("30%",),
        "input_markers": ("25000", "2000", "5500"),
    },
    "opt-mixed-periods": {
        "dimension": "material_normalization",
        "required": {"normalize": 0.2, "ratio": 0.2, "recommendation": 0.4, "evidence": 0.2},
        "forbidden": {"raw_period_mix": 1.0, "invented_facts": 0.5, "over_escalation": 0.6},
        "answer_markers": ("45%",),
        "input_markers": ("240000", "9000", "6000"),
    },
    "opt-duplicate-debt": {
        "dimension": "record_identity",
        "required": {"dedupe": 0.25, "ratio": 0.25, "recommendation": 0.25, "evidence": 0.25},
        "forbidden": {"duplicate_count": 1.0, "invented_facts": 0.5, "over_escalation": 0.6},
        "answer_markers": ("48.33%",),
        "input_markers": ("L02", "5200", "1800", "3500"),
    },
    "opt-superseded-record": {
        "dimension": "record_time_validity",
        "required": {"supersession": 0.2, "ratio": 0.2, "recommendation": 0.4, "evidence": 0.2},
        "forbidden": {"stale_overdue": 1.0, "ignore_relationship": 0.5, "over_escalation": 0.6},
        "answer_markers": ("40%",),
        "input_markers": ("C11", "2026-03-01", "2026-06-01", "20000", "5000"),
    },
    "opt-duplicate-transfer": {
        "dimension": "record_identity",
        "required": {"dedupe": 0.2, "ratio": 0.2, "recommendation": 0.4, "evidence": 0.2},
        "forbidden": {"duplicate_count": 1.0, "invented_facts": 0.5, "over_escalation": 0.6},
        "answer_markers": ("46.67%",),
        "input_markers": ("K01", "K02", "2500", "1200", "1500", "3000"),
    },
    "hold-mixed-periods": {
        "dimension": "material_normalization",
        "required": {"normalize": 0.2, "ratio": 0.2, "recommendation": 0.4, "evidence": 0.2},
        "forbidden": {"raw_period_mix": 1.0, "invented_facts": 0.5, "over_escalation": 0.6},
        "answer_markers": ("43.33%",),
        "input_markers": ("180000", "6000", "4500"),
    },
    "hold-duplicate-debt": {
        "dimension": "record_identity",
        "required": {"dedupe": 0.2, "ratio": 0.2, "recommendation": 0.4, "evidence": 0.2},
        "forbidden": {"duplicate_count": 1.0, "invented_facts": 0.5, "over_escalation": 0.6},
        "answer_markers": ("41.67%",),
        "input_markers": ("L05", "6000", "2400", "4000"),
    },
    "hold-superseded-record": {
        "dimension": "record_time_validity",
        "required": {"supersession": 0.2, "ratio": 0.2, "recommendation": 0.4, "evidence": 0.2},
        "forbidden": {"stale_overdue": 1.0, "ignore_relationship": 0.5, "over_escalation": 0.6},
        "answer_markers": ("38%",),
        "input_markers": ("D07", "2026-02-01", "2026-05-01", "25000", "6000"),
    },
    "hold-precision-boundary": {
        "dimension": "precision_boundary",
        "required": {"boundary": 0.3, "calculation": 0.25, "recommendation": 0.25, "evidence": 0.2},
        "forbidden": {"rounded_miss": 1.0, "invented_facts": 0.5},
        "answer_markers": ("50.004%",),
        "input_markers": ("25000", "7000", "5501"),
    },
}

MATERIAL_NAMES = (
    "case-intake",
    "judge-rubric",
    "failure-feedback",
    "tool-contract",
    "optimization-charter",
    "release-approval",
)


# ---------------------------------------------------------------------------
# 通用读取与包引用
# ---------------------------------------------------------------------------


def load_business():
    spec = importlib.util.spec_from_file_location("sample25_business", SAMPLE_DIR / "main.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read_yaml(path: str | Path):
    return yaml.safe_load(Path(path).read_text(encoding="utf-8"))


def read_json(path: str | Path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def load_mapping(path: str | Path) -> dict:
    """读取对象型 YAML；样例中的 state/report/refs 均采用此结构。"""
    target = Path(path).expanduser().resolve()
    if not target.is_file():
        raise ValueError(f"必需文件不存在: {target}")
    data = read_yaml(target)
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


def write_single_package_refs(path: Path, package: Path) -> Path:
    """写入绝对路径的单 solver refs 文件，供离线验收夹具使用。"""
    path.write_text(
        yaml.safe_dump({"harness_refs": {"solver": str(Path(package).resolve())}}),
        encoding="utf-8",
    )
    return path


# ---------------------------------------------------------------------------
# Skill 目录静态契约（业界标准目录：SKILL.md + references/ + scripts/）
# ---------------------------------------------------------------------------


def parse_skill_frontmatter(text: str) -> dict:
    """解析 SKILL.md 的 YAML frontmatter；缺失或非法直接报错。"""
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        raise ValueError("SKILL.md 必须以 YAML frontmatter 开头")
    try:
        closing = lines.index("---", 1)
    except ValueError as exc:
        raise ValueError("SKILL.md frontmatter 未闭合") from exc
    payload = yaml.safe_load("\n".join(lines[1:closing]))
    if not isinstance(payload, dict):
        raise ValueError("SKILL.md frontmatter 必须是对象")
    return payload


def load_manifest(package_dir: str | Path) -> tuple[Path, dict]:
    """读取 Plugin manifest；本样例只允许 Prompt/Skill 资源面。"""
    package = Path(package_dir).expanduser().resolve()
    manifest_path = package / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError(f"缺少 Plugin manifest: {manifest_path}")
    manifest = read_json(manifest_path)
    if not isinstance(manifest, dict):
        raise ValueError(f"manifest.json 顶层必须是对象: {manifest_path}")
    if manifest.get("package_type") != "plugin":
        raise ValueError(f"manifest.json package_type 必须是 plugin: {manifest_path}")
    # 0.1.20 会在候选/发布包里同步生成空的 tools/rails 注册表；
    # 只允许空声明，出现实际资源即验收失败。
    forbidden = sorted(
        key for key in FORBIDDEN_MANIFEST_KEYS
        if manifest.get(key) not in (None, [], {}, "")
    )
    if forbidden:
        raise ValueError(f"manifest.json 不得声明 {forbidden} 资源（本样例只允许 Prompt/Skill）")
    return package, manifest


def manifest_skill_dirs(package: Path, manifest: dict) -> list[Path]:
    skills = manifest.get("skills")
    if not isinstance(skills, list) or not skills:
        raise ValueError("manifest.json 必须声明 skills[].dir")
    directories: list[Path] = []
    for entry in skills:
        if not isinstance(entry, dict):
            raise ValueError("manifest.json skills 条目必须是对象")
        raw = str(entry.get("dir") or "").strip()
        if not raw:
            raise ValueError("manifest.json skills[].dir 不能为空")
        directory = (package / raw).resolve()
        if not directory.is_relative_to(package):
            raise ValueError(f"技能目录越界: {raw}")
        if directory in directories:
            raise ValueError(f"技能目录重复声明: {raw}")
        directories.append(directory)
    return directories


def run_dti_script(skill_dir: str | Path, *arguments: str) -> dict:
    """离线运行技能内置确定性脚本并解析 JSON 输出。"""
    directory = Path(skill_dir).expanduser().resolve()
    script = directory / "scripts/dti.py"
    if not script.is_file():
        raise ValueError(f"缺少确定性计算脚本: {script}")
    completed = subprocess.run(
        [sys.executable, str(script), *arguments],
        cwd=str(directory), capture_output=True, text=True, timeout=120,
    )
    if completed.returncode != 0:
        raise ValueError(
            f"scripts/dti.py 运行失败（退出码 {completed.returncode}）: {completed.stderr.strip()}")
    try:
        payload = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise ValueError(f"scripts/dti.py 输出不是 JSON: {completed.stdout[:500]}") from exc
    if not isinstance(payload, dict):
        raise ValueError("scripts/dti.py 输出的 JSON 顶层必须是对象")
    return payload


def assert_dti_contract(payload: dict) -> dict:
    """校验标准月化输入下的确定性输出（45.0% / 0.45 / false）。"""
    expected = {
        "monthly_income": 20000.0,
        "existing_monthly_debt": 3000.0,
        "new_monthly_payment": 6000.0,
        "total_monthly_debt_service": 9000.0,
    }
    for key, value in expected.items():
        actual = payload.get(key)
        if isinstance(actual, bool) or not isinstance(actual, int | float):
            raise ValueError(f"scripts/dti.py 输出 {key} 必须是数值，实际 {actual!r}")
        if abs(float(actual) - value) > 1e-6:
            raise ValueError(f"scripts/dti.py 输出 {key} 期望 {value}，实际 {actual!r}")
    ratio = payload.get("debt_service_ratio")
    raw_ratio = payload.get("raw_ratio")
    above = payload.get("above_50_percent")
    if isinstance(ratio, bool) or not isinstance(ratio, int | float) or abs(float(ratio) - 45.0) > 1e-6:
        raise ValueError(f"scripts/dti.py 输出 debt_service_ratio 期望 45.0，实际 {ratio!r}")
    if isinstance(raw_ratio, bool) or not isinstance(raw_ratio, int | float) or abs(float(raw_ratio) - 0.45) > 1e-9:
        raise ValueError(f"scripts/dti.py 输出 raw_ratio 期望 0.45，实际 {raw_ratio!r}")
    if above is not False:
        raise ValueError(f"scripts/dti.py 输出 above_50_percent 期望 false，实际 {above!r}")
    return payload


def assert_dti_boundary_contract(payload: dict) -> None:
    """精度边界：原始比值 50.004% 必须判超线，不能被展示舍入掩盖。"""
    total = payload.get("total_monthly_debt_service")
    raw_ratio = payload.get("raw_ratio")
    ratio = payload.get("debt_service_ratio")
    above = payload.get("above_50_percent")
    if total != 12501:
        raise ValueError(f"scripts/dti.py 合计月债务应得 12501，实际 {total!r}")
    if isinstance(raw_ratio, bool) or not isinstance(raw_ratio, int | float):
        raise ValueError("scripts/dti.py 必须输出数值 raw_ratio")
    if abs(float(raw_ratio) - 0.50004) > 1e-9:
        raise ValueError(f"scripts/dti.py raw_ratio 期望 0.50004，实际 {raw_ratio!r}")
    if isinstance(ratio, bool) or not isinstance(ratio, int | float) or float(ratio) != 50.0:
        raise ValueError(f"scripts/dti.py debt_service_ratio 展示应保留两位小数为 50.0，实际 {ratio!r}")
    if above is not (float(raw_ratio) > 0.5):
        raise ValueError("scripts/dti.py above_50_percent 必须按未舍入 raw_ratio 严格判断")


def validate_skill_directory(skill_dir: str | Path, *, expected_name: str | None = None) -> dict:
    """校验一个业界标准 Skill 目录并离线跑通 scripts/dti.py。"""
    directory = Path(skill_dir).expanduser().resolve()
    if not directory.is_dir():
        raise ValueError(f"Skill 目录不存在: {directory}")
    texts: dict[str, str] = {}
    for relative in SKILL_REQUIRED_FILES:
        path = directory / relative
        if not path.is_file():
            raise ValueError(f"Skill 目录缺少标准文件 {relative}: {directory}")
        text = path.read_text(encoding="utf-8")
        if not text.strip():
            raise ValueError(f"Skill 标准文件为空: {path}")
        texts[relative] = text
    frontmatter = parse_skill_frontmatter(texts["SKILL.md"])
    name = str(frontmatter.get("name") or "").strip()
    if not name:
        raise ValueError(f"SKILL.md frontmatter 缺少 name: {directory / 'SKILL.md'}")
    if not str(frontmatter.get("description") or "").strip():
        raise ValueError(f"SKILL.md frontmatter 缺少 description: {directory / 'SKILL.md'}")
    if name != directory.name:
        raise ValueError(f"SKILL.md name={name!r} 必须与技能目录名 {directory.name!r} 一致")
    if expected_name is not None and name != expected_name:
        raise ValueError(f"SKILL.md name 必须是 {expected_name!r}，实际 {name!r}")
    script_path = directory / "scripts/dti.py"
    try:
        compile(texts["scripts/dti.py"], str(script_path), "exec")
    except SyntaxError as exc:
        raise ValueError(f"scripts/dti.py 无法编译: {script_path}") from exc
    payload = run_dti_script(directory, *DTI_DEFAULT_ARGS)
    assert_dti_contract(payload)
    return payload


def validate_final_artifact(refs_path: str | Path) -> tuple[Path, object]:
    """最终包复装：标准 Skill 目录完整、脚本可运行、官方加载器可读回。"""
    package = resolve_single_package(refs_path)
    package, manifest = load_manifest(package)
    directories = manifest_skill_dirs(package, manifest)
    target = next((directory for directory in directories if directory.name == SKILL_DIR_NAME), None)
    if target is None:
        raise ValueError(f"最终包缺少标准技能目录 {SKILL_DIR_NAME}: {package}")
    validate_skill_directory(target, expected_name=SKILL_DIR_NAME)
    from openjiuwen.harness.resources import find_plugin_manifest, load_plugin_package

    plugin = load_plugin_package(find_plugin_manifest(package))
    if not getattr(plugin, "prompt_sections", None):
        raise ValueError(f"官方加载器未读回 Prompt: {package}")
    if not getattr(plugin, "skills", None):
        raise ValueError(f"官方加载器未读回 Skill: {package}")
    loaded_dirs = {str(Path(skill.dir).expanduser().resolve()) for skill in plugin.skills}
    if loaded_dirs != {str(directory) for directory in directories}:
        raise ValueError(f"官方加载器解析的 Skill 目录与 manifest 声明不一致: {package}")
    return package, plugin


# ---------------------------------------------------------------------------
# 运行证据校验（state/report、全量回放、发布门槛）
# ---------------------------------------------------------------------------


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


def evaluation_result_path(eval_ref_path: str | Path, case: dict) -> Path:
    raw = str(case.get("result_path") or "").strip()
    case_id = str(case.get("case_id") or "")
    if not raw:
        raise ValueError(f"{case_id} 缺少 result_path")
    path = Path(raw).expanduser()
    if not path.is_absolute():
        path = Path(eval_ref_path).expanduser().resolve().parent / path
    path = path.resolve()
    if not path.is_file():
        raise ValueError(f"{case_id} 缺少 result.json: {path}")
    return path


def validate_evaluation(eval_ref_path: str | Path, expected_case_ids: set[str]) -> dict:
    """校验真实执行与 LLM 连续评审证据；业务低分可完成，执行/评审故障不可完成。"""
    expected = {str(case_id) for case_id in expected_case_ids}
    eval_ref = load_mapping(eval_ref_path)
    cases = eval_ref.get("cases")
    if not isinstance(cases, list) or len(cases) != len(expected):
        raise ValueError(f"评测案例数不完整: {eval_ref_path}")
    actual_ids = [str(case.get("case_id") or "") for case in cases]
    if set(actual_ids) != expected or len(set(actual_ids)) != len(actual_ids):
        raise ValueError(f"评测 case_id 不匹配: {actual_ids} != {sorted(expected)}")
    summary_path = Path(str(eval_ref.get("summary_path") or "")).expanduser().resolve()
    if not summary_path.is_file():
        raise ValueError(f"缺少评测 summary: {summary_path}")
    summary = read_json(summary_path)
    if not isinstance(summary, dict):
        raise ValueError(f"评测 summary 顶层必须是对象: {summary_path}")
    if summary.get("total_cases") != len(expected):
        raise ValueError(f"评测 summary total_cases 不完整: {summary_path}")
    average = summary.get("average_score")
    if isinstance(average, bool) or not isinstance(average, int | float) or not 0.0 <= float(average) <= 1.0:
        raise ValueError(f"评测 summary 缺少 0~1 连续平均分: {summary_path}")
    method = summary.get("evaluation_method")
    if method not in (None, "", "llm_as_judge"):
        raise ValueError(f"评测 summary 不是 llm_as_judge: {method!r}")
    scores = []
    for case in cases:
        case_id = str(case["case_id"])
        if case.get("status") not in {"passed", "failed"}:
            raise ValueError(f"{case_id} 状态不可验收: {case.get('status')}")
        metadata = case.get("metadata") or {}
        if metadata.get("execution_status") != "passed":
            raise ValueError(f"{case_id} execution_status 不是 passed，不得当作业务低分")
        if str(metadata.get("evaluation_method") or "") != "llm_as_judge":
            raise ValueError(f"{case_id} 缺少真实 llm_as_judge 评审")
        score = case.get("score")
        if isinstance(score, bool) or not isinstance(score, int | float) or not 0.0 <= float(score) <= 1.0:
            raise ValueError(f"{case_id} 缺少 0~1 连续分数")
        scores.append(float(score))
        result_path = evaluation_result_path(eval_ref_path, case)
        result = read_json(result_path)
        if not isinstance(result, dict):
            raise ValueError(f"{case_id} result.json 顶层必须是对象")
        if result.get("execution_status") != "passed" or result.get("error"):
            raise ValueError(f"{case_id} 存在执行错误，不得进入业务归因")
        if result.get("status") not in {"passed", "failed"}:
            raise ValueError(f"{case_id} result.json 状态不可验收: {result.get('status')}")
        evaluation = result.get("evaluation") or {}
        if evaluation.get("method") != "llm_as_judge":
            raise ValueError(f"{case_id} result.json 缺少 llm_as_judge 证据")
        evaluation_metadata = evaluation.get("metadata") or {}
        if evaluation_metadata.get("score_mode") != "continuous":
            raise ValueError(f"{case_id} 缺少连续评分证据（score_mode != continuous）")
        result_score = result.get("score")
        if result_score != score:
            raise ValueError(f"{case_id} eval_ref 与 result.json 分数不一致: {score!r} != {result_score!r}")
    expected_average = sum(scores) / len(scores)
    if abs(float(average) - expected_average) > 1e-9:
        raise ValueError(f"评测 summary average_score 与案例分数不一致: {average!r} != {expected_average!r}")
    return summary


def evaluation_scores(eval_ref_path: str | Path, expected_case_ids: set[str]) -> dict[str, float]:
    """只读取 eval_ref 的 case/score 集合，用于基线与全量回放的逐案比较。"""
    expected = {str(case_id) for case_id in expected_case_ids}
    eval_ref = load_mapping(eval_ref_path)
    cases = eval_ref.get("cases")
    if not isinstance(cases, list):
        raise ValueError(f"eval_ref.cases 必须是列表: {eval_ref_path}")
    scores: dict[str, float] = {}
    for case in cases:
        case_id = str(case.get("case_id") or "")
        score = case.get("score")
        if not case_id or case_id in scores:
            raise ValueError(f"eval_ref 存在缺失或重复的 case_id: {eval_ref_path}")
        if isinstance(score, bool) or not isinstance(score, int | float) or not 0.0 <= float(score) <= 1.0:
            raise ValueError(f"{case_id} 缺少 0~1 连续分数: {eval_ref_path}")
        scores[case_id] = float(score)
    if set(scores) != expected:
        raise ValueError(f"评测 case_id 不匹配: {sorted(scores)} != {sorted(expected)}")
    return scores


def triggered_forbidden_behaviors(result_path: str | Path) -> set[str]:
    """从裁判证据中读取某案例触发的 forbidden 行为 id。"""
    result = read_json(result_path)
    metadata = (result.get("evaluation") or {}).get("metadata") or {}
    triggered: set[str] = set()
    parsed = metadata.get("parsed") or {}
    for hit in parsed.get("forbidden_hits") or []:
        if isinstance(hit, dict) and hit.get("triggered") is True:
            triggered.add(str(hit.get("id") or ""))
    requirement_results = metadata.get("requirement_results") or {}
    items = requirement_results.get("items") if isinstance(requirement_results, dict) else requirement_results
    for item in items or []:
        if not isinstance(item, dict):
            continue
        if item.get("group") == "forbidden" and item.get("passed") is False:
            triggered.add(str(item.get("requirement_id") or ""))
    triggered.discard("")
    return triggered


def forbidden_triggers(eval_ref_path: str | Path) -> dict[str, set[str]]:
    eval_ref = load_mapping(eval_ref_path)
    triggers: dict[str, set[str]] = {}
    for case in eval_ref.get("cases") or []:
        if not isinstance(case, dict):
            continue
        case_id = str(case.get("case_id") or "")
        triggers[case_id] = triggered_forbidden_behaviors(evaluation_result_path(eval_ref_path, case))
    return triggers


def validate_no_new_forbidden(
        baseline_eval_ref: str | Path, full_eval_ref: str | Path, expected_case_ids: set[str]) -> None:
    """最终全量回放不得新增任何 forbidden 触发。"""
    baseline = forbidden_triggers(baseline_eval_ref)
    final = forbidden_triggers(full_eval_ref)
    for case_id in sorted({str(case_id) for case_id in expected_case_ids}):
        new_hits = final.get(case_id, set()) - baseline.get(case_id, set())
        if new_hits:
            raise ValueError(
                f"最终全量回放中 {case_id} 新增 forbidden 触发: {sorted(new_hits)}；"
                f"基线触发={sorted(baseline.get(case_id, set()))}，最终触发={sorted(final.get(case_id, set()))}")


def validate_stage_improvement(
        *,
        baseline_eval_ref: str | Path,
        full_eval_ref: str | Path,
        expected_case_ids: set[str],
        challenge_case_ids: set[str],
        protected_case_ids: set[str],
) -> dict[str, float]:
    """published 时的业务门槛：挑战案实质提升、保护案不退化、无新增 forbidden。"""
    expected = {str(case_id) for case_id in expected_case_ids}
    challenge = {str(case_id) for case_id in challenge_case_ids}
    protected = {str(case_id) for case_id in protected_case_ids}
    if not challenge or not protected:
        raise ValueError("挑战案与保护案集合都不能为空")
    if not challenge <= expected or not protected <= expected:
        raise ValueError("挑战案/保护案必须属于本次冻结优化集")
    if challenge & protected:
        raise ValueError("挑战案与保护案不能重叠")
    baseline = evaluation_scores(baseline_eval_ref, expected)
    full = evaluation_scores(full_eval_ref, expected)
    deltas = {case_id: full[case_id] - baseline[case_id] for case_id in expected}
    improved = [
        case_id for case_id in sorted(challenge)
        if deltas[case_id] + EPSILON >= CHALLENGE_IMPROVEMENT_THRESHOLD
    ]
    if len(improved) < CHALLENGE_IMPROVEMENT_REQUIRED:
        raise ValueError(
            f"published 全量回放中挑战案仅 {len(improved)} 个相对基线提升 ≥ "
            f"{CHALLENGE_IMPROVEMENT_THRESHOLD}（要求至少 {CHALLENGE_IMPROVEMENT_REQUIRED} 个）: "
            f"{ {case_id: round(deltas[case_id], 4) for case_id in sorted(challenge)} }")
    for case_id in sorted(protected):
        floor = baseline[case_id] - PROTECTED_REGRESSION_TOLERANCE
        if full[case_id] + EPSILON < floor:
            raise ValueError(
                f"published 全量回放中保护案 {case_id} 退化: "
                f"baseline={baseline[case_id]:.4f}，final={full[case_id]:.4f}，"
                f"允许下限={floor:.4f}")
    validate_no_new_forbidden(baseline_eval_ref, full_eval_ref, expected)
    return deltas


def _find_published_gate(report: dict) -> dict:
    gates = [
        gate for gate in report.get("candidate_gates") or []
        if gate.get("accepted") and gate.get("status") == "accepted"
    ]
    if not gates:
        raise ValueError("published 但候选门禁中没有 accepted 候选")
    best_refs = str(report.get("best_harness_refs_path") or "")
    best_eval = str(report.get("best_eval_ref_path") or "")
    matches = [
        gate for gate in gates
        if str(gate.get("candidate_harness_refs_path") or "") == best_refs
    ]
    if not matches:
        matches = [
            gate for gate in gates
            if str(gate.get("full_eval_ref_path") or "") == best_eval
        ]
    if not matches:
        raise ValueError("published 缺少与最佳包/全量回放对应的 accepted 候选证据")
    gate = matches[-1]
    if best_eval and str(gate.get("full_eval_ref_path") or "") != best_eval:
        raise ValueError("published 的最佳全量回放与 accepted 候选证据不一致")
    return gate


def validate_run_evidence(result, expected_case_ids: set[str], source_refs: str) -> tuple[dict, str]:
    """交叉检查官方 state/report、全量基线、候选门禁与发布证据。"""
    expected = {str(case_id) for case_id in expected_case_ids}
    state = load_mapping(result.state_path)
    report = load_mapping(result.report_path)
    if state.get("status") != "completed" or report.get("status") != "completed":
        raise ValueError("RSI state/report 未达到 completed")
    consistency_keys = (
        "baseline_score", "best_score", "baseline_eval_ref_path", "best_eval_ref_path",
        "best_harness_refs_path", "current_harness_refs_path",
        "published_harness_refs_path", "publication_status", "candidate_gates",
        "best_improvement_proof",
    )
    for key in consistency_keys:
        if state.get(key) != report.get(key):
            raise ValueError(f"state/report 字段不一致: {key}")
    if not isinstance(report.get("baseline_score"), int | float) or not isinstance(
            report.get("best_score"), int | float):
        raise ValueError("基线或最佳分数缺失")
    if not 0.0 <= float(report["baseline_score"]) <= 1.0 or not 0.0 <= float(report["best_score"]) <= 1.0:
        raise ValueError("基线或最佳分数不在 0~1 尺度")
    if report.get("analysis_unavailable_count") or report.get("analysis_failures"):
        raise ValueError("存在分析/评审故障，不得把本次运行视为已验收闭环")
    validate_evaluation(report["baseline_eval_ref_path"], expected)
    if report.get("best_eval_ref_path") != report.get("baseline_eval_ref_path"):
        validate_evaluation(report["best_eval_ref_path"], expected)
    gates = report.get("candidate_gates") or []
    for gate in gates:
        if gate.get("status") in {"inconclusive", "skipped", "error"}:
            raise ValueError(f"候选门禁存在不可验收状态: {gate.get('status')}")
        candidate_refs = str(gate.get("candidate_harness_refs_path") or "")
        if candidate_refs:
            candidate_package = resolve_single_package(candidate_refs)
            assert_prompt_skill_only(SOURCE_PACKAGE, candidate_package)
        if gate.get("accepted") or gate.get("status") == "accepted":
            for key in ("candidate_harness_refs_path", "candidate_eval_ref_path", "full_eval_ref_path"):
                if not Path(str(gate.get(key) or "")).is_file():
                    raise ValueError(f"accepted 候选缺少证据: {key}")
            if not (gate.get("improvement_proof") or {}).get("qualified"):
                raise ValueError("accepted 候选缺少 qualified 改进证明")
            validate_evaluation(gate["full_eval_ref_path"], expected)
    final_refs = select_final_refs(report, source_refs)
    if not Path(final_refs).is_file():
        raise ValueError(f"最终 Harness refs 不存在: {final_refs}")
    if report["publication_status"] == "published":
        if not report.get("accepted_candidate_count") or report["best_score"] <= report["baseline_score"]:
            raise ValueError("published 必须有 accepted 候选且最佳分高于基线")
        proof = report.get("best_improvement_proof") or {}
        if proof.get("qualified") is not True or proof.get("scope") != "target_cases":
            raise ValueError("published 缺少限定在目标案例上的合格改进证明")
        published_gate = _find_published_gate(report)
        if published_gate.get("full_engineering_passed") is not True:
            raise ValueError("accepted 候选缺少全量工程校验证据")
        if (published_gate.get("full_checks") or {}).get("accepted") is not True:
            raise ValueError("accepted 候选缺少全量回放通过证据")
        challenge = set(CHALLENGE_CASE_IDS) & expected
        protected = set(PROTECTED_CASE_IDS) & expected
        if challenge and protected:
            validate_stage_improvement(
                baseline_eval_ref=report["baseline_eval_ref_path"],
                full_eval_ref=published_gate["full_eval_ref_path"],
                expected_case_ids=expected,
                challenge_case_ids=challenge,
                protected_case_ids=protected,
            )
    return report, final_refs


# ---------------------------------------------------------------------------
# 100 分制展示
# ---------------------------------------------------------------------------


def hundred_point_score(score) -> float:
    """把 0~1 原始分数转换为 100 分制展示（保留两位小数）；断言比较仍用原始值。"""
    if isinstance(score, bool) or not isinstance(score, int | float):
        raise ValueError(f"分数必须是 0~1 的数值，实际 {score!r}")
    if not 0.0 <= float(score) <= 1.0:
        raise ValueError(f"分数必须在 0~1 之间，实际 {score!r}")
    return round(float(score) * 100, 2)


def hundred_point_scores(scores: dict[str, float]) -> dict[str, float]:
    return {name: hundred_point_score(score) for name, score in scores.items()}


# ---------------------------------------------------------------------------
# 静态契约测试（离线，不调用模型）
# ---------------------------------------------------------------------------


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
            "harness/credit_review/prompt_sections/files/credit_review.md",
            "harness/credit_review/prompt_sections/sections.yaml",
            "data/optimization_cases.json",
            "data/holdout_cases.json",
        ]
        required.extend(f"harness/credit_review/skills/{SKILL_DIR_NAME}/{relative}"
                        for relative in SKILL_REQUIRED_FILES)
        extensions = {"case-intake": "json", "judge-rubric": "json",
                      "failure-feedback": "md", "tool-contract": "yaml",
                      "optimization-charter": "yaml", "release-approval": "md"}
        for name in MATERIAL_NAMES:
            extension = extensions[name]
            required.append(f"materials/{name}.template.{extension}")
            required.append(f"materials/{name}.example.{extension}")
        for relative in required:
            self.require_file(relative)

    def test_skill_directory_follows_industry_layout(self):
        payload = validate_skill_directory(SKILL_DIR, expected_name=SKILL_DIR_NAME)
        self.assertEqual(45.0, payload["debt_service_ratio"])
        self.assertEqual(0.45, payload["raw_ratio"])
        self.assertIs(False, payload["above_50_percent"])

        skill_text = (SKILL_DIR / "SKILL.md").read_text(encoding="utf-8")
        for reference in ("references/policy-demo.md", "references/output-format.md", "scripts/dti.py"):
            self.assertIn(reference, skill_text, f"SKILL.md 必须用相对路径指引 {reference}")
        self.assertIn("skill_tool", skill_text, "SKILL.md 必须说明用 skill_tool 读取技能内文件")

        policy_text = (SKILL_DIR / "references/policy-demo.md").read_text(encoding="utf-8")
        self.assertIn("DEMO-CREDIT-2026.1", policy_text)
        self.assertIn("50%", policy_text)
        self.assertIn("逾期", policy_text)
        self.assertIn("建议补充材料", policy_text)
        self.assertIn("建议人工复核", policy_text)
        self.assertIn("建议进入后续审批", policy_text)

        format_text = (SKILL_DIR / "references/output-format.md").read_text(encoding="utf-8")
        for heading in OUTPUT_HEADINGS:
            self.assertIn(heading, format_text, f"output-format.md 必须固定六段标题 {heading}")
        self.assertIn("DEMO-CREDIT-2026.1", format_text)

    def test_dti_script_offline_contract_and_precision_boundary(self):
        payload = run_dti_script(SKILL_DIR, *DTI_DEFAULT_ARGS)
        assert_dti_contract(payload)
        boundary = run_dti_script(SKILL_DIR, *DTI_BOUNDARY_ARGS)
        assert_dti_boundary_contract(boundary)

        invalid = subprocess.run(
            [sys.executable, str(SKILL_DIR / "scripts/dti.py"),
             "--income", "0", "--existing-debt", "3000", "--new-payment", "6000"],
            cwd=str(SKILL_DIR), capture_output=True, text=True, timeout=120,
        )
        self.assertNotEqual(0, invalid.returncode, "income<=0 时 scripts/dti.py 必须报错退出")
        self.assertTrue(invalid.stderr.strip(), "scripts/dti.py 报错时必须给出错误信息")

    def test_manifest_declares_prompt_and_skill_surfaces_only(self):
        refs = read_yaml(self.require_file("harness/harness_refs.yaml"))["harness_refs"]
        self.assertEqual({"solver"}, set(refs))
        package = (SAMPLE / "harness" / refs["solver"]).resolve()
        self.assertEqual(SOURCE_PACKAGE.resolve(), package)
        package, manifest = load_manifest(package)
        self.assertFalse(set(FORBIDDEN_MANIFEST_KEYS) & set(manifest))
        prompt_sections = manifest["prompt_sections"]
        self.assertEqual(1, len(prompt_sections))
        prompt = prompt_sections[0]
        self.assertTrue(str(prompt["file"]).startswith("prompt_sections/"))
        self.assertTrue((package / prompt["file"]).is_file())
        directories = manifest_skill_dirs(package, manifest)
        self.assertEqual([SKILL_DIR.resolve()], directories)
        mode = manifest["skills"][0].get("mode")
        self.assertEqual("all", mode)
        metadata = manifest.get("metadata") or {}
        self.assertEqual({"prompt", "skill"}, set(metadata.get("optimizable_surfaces") or []))
        self.assertEqual("DEMO-CREDIT-2026.1", metadata.get("policy_version"))

    def test_prompt_guides_agent_to_use_pre_loan_review_skill(self):
        package, manifest = load_manifest(SOURCE_PACKAGE)
        prompt_path = package / manifest["prompt_sections"][0]["file"]
        text = prompt_path.read_text(encoding="utf-8")
        self.assertIn("skill_tool", text, "Prompt 必须要求先调用 skill_tool 读取技能")
        self.assertIn("pre_loan_review", text, "Prompt 必须点名 pre_loan_review 技能")
        self.assertIn("DEMO-CREDIT-2026.1", text)
        self.assertTrue(
            "六段" in text or "output-format" in text or any(heading in text for heading in OUTPUT_HEADINGS),
            "Prompt 必须要求按技能 references/output-format.md 的六段结构输出",
        )

    def test_skill_package_does_not_leak_evaluation_material(self):
        forbidden_tokens = ("rubric", "case_id", "required_behaviors", "forbidden_behaviors")
        case_ids = OPTIMIZATION_CASE_IDS | HOLDOUT_CASE_IDS
        for path in sorted(SKILL_DIR.rglob("*")):
            if not path.is_file() or path.suffix not in {".md", ".py", ".txt", ".json", ".yaml", ".yml"}:
                continue
            text = path.read_text(encoding="utf-8")
            lowered = text.lower()
            for token in forbidden_tokens:
                self.assertNotIn(token, lowered, f"{path} 泄漏评测内部字段 {token}")
            for case_id in case_ids:
                self.assertNotIn(case_id, text, f"{path} 泄漏评测 case_id {case_id}")

    def test_official_loader_reads_prompt_and_skill_package(self):
        from openjiuwen.harness.resources import find_plugin_manifest, load_plugin_package

        plugin = load_plugin_package(find_plugin_manifest(SOURCE_PACKAGE))
        self.assertTrue(plugin.prompt_sections)
        self.assertTrue(plugin.skills)
        self.assertEqual(
            {str(SKILL_DIR.resolve())},
            {str(Path(skill.dir).expanduser().resolve()) for skill in plugin.skills},
        )

    def test_model_config_contains_only_environment_credentials(self):
        path = self.require_file("config/model.yaml")
        config = read_yaml(path)
        client = config["model"]["model_client_config"]
        request = config["model"]["model_request_config"]
        self.assertEqual("${API_KEY}", client["api_key"])
        self.assertEqual("${API_BASE}", client["api_base"])
        self.assertEqual("${MODEL_NAME}", request["model"])
        self.assertIs(False, client["supports_vision"])
        self.assertEqual(0, request["temperature"])
        text = path.read_text(encoding="utf-8")
        self.assertNotIn("sk-", text)
        self.assertNotIn("api_key:", text.replace("api_key: ${API_KEY}", ""))

    def test_rsi_config_matches_single_batch_acceptance(self):
        config = read_yaml(self.require_file("config/rsi.yaml"))
        self.assertEqual(1, config["max_epochs"])
        self.assertEqual(6, config["data_loader"]["batch_size"])
        self.assertEqual(6, len(read_json(OPTIMIZATION_DATA)["cases"]))
        self.assertEqual(["prompt", "skill"], config["member_optimizer"]["action_group_configs"])
        evaluator = config["evaluator"]
        self.assertEqual("single_harness", evaluator["backend"])
        self.assertEqual("llm_as_judge", evaluator["evaluation_method"])
        self.assertEqual("continuous", evaluator["score_mode"])
        self.assertIs(False, evaluator["judge_baseline_comparison"])
        self.assertGreaterEqual(evaluator["solver_max_iterations_hard_limit"]
                                if "solver_max_iterations_hard_limit" in evaluator
                                else evaluator["solver_max_iterations"], 1)
        self.assertLessEqual(evaluator["judge_agent_max_iterations"], 12)
        self.assertTrue(config["scheduling"]["full_evaluation_enabled"])
        promotion = config["promotion"]
        self.assertEqual("continuous_two_stage_v2", promotion["policy_version"])
        self.assertGreater(promotion["min_improvement"], 0)
        self.assertGreater(promotion["min_improvement"], 0)
        self.assertGreaterEqual(promotion["non_target_max_regression"], 0)
        self.assertLessEqual(promotion["non_target_max_regression"], PROTECTED_REGRESSION_TOLERANCE)

    def test_datasets_are_frozen_scored_and_holdout_is_disjoint(self):
        datasets: dict[str, set[str]] = {}
        expected_splits = {"optimization": OPTIMIZATION_CASE_IDS, "holdout": HOLDOUT_CASE_IDS}
        for split, expected_ids in expected_splits.items():
            data = read_json(self.require_file(f"data/{split}_cases.json"))
            self.assertTrue(str(data.get("dataset_id") or "").strip(), f"{split} 数据集缺少 dataset_id")
            self.assertIs(data.get("synthetic"), True)
            self.assertIs(data.get("deidentified"), True)
            self.assertTrue(str(data.get("policy_version") or "").strip())
            cases = data["cases"]
            self.assertEqual(len(expected_ids), len(cases))
            case_ids = [case["case_id"] for case in cases]
            self.assertEqual(len(case_ids), len(set(case_ids)), f"{split} case_id 必须唯一")
            self.assertEqual(set(expected_ids), set(case_ids))
            datasets[split] = set(case_ids)
            for case in cases:
                self.assertIn(case["case_id"], CASE_CONTRACTS, "每个冻结案例必须有验收契约")
                self.assertIsInstance(case["input"], str)
                self.assertTrue(case["input"].strip())
                self.assertIs(case["synthetic"], True)
                self.assertIs(case["deidentified"], True)
                reference = case["reference"]
                self.assertTrue(reference["answer"])
                self.assertEqual("reference", reference["answer_role"])
                self.assertEqual("subtract", reference["penalty_mode"])
                self.assertTrue(reference["policy_version"])
                behaviors = reference["required_behaviors"]
                self.assertGreaterEqual(len(behaviors), 4)
                self.assertAlmostEqual(1.0, sum(item["weight"] for item in behaviors), places=9)
                self.assertTrue(all(item["description"] for item in behaviors))
                self.assertEqual(len(behaviors), len({item["id"] for item in behaviors}))
                forbidden = reference["forbidden_behaviors"]
                self.assertTrue(forbidden)
                self.assertEqual(len(forbidden), len({item["id"] for item in forbidden}))
                for item in forbidden:
                    self.assertIsInstance(item["penalty"], int | float)
                    self.assertGreaterEqual(item["penalty"], 0.0)
                    self.assertLessEqual(item["penalty"], 1.0)
                contract = CASE_CONTRACTS[case["case_id"]]
                self.assertEqual(contract["dimension"], case.get("dimension"))
                self.assertEqual(contract["required"],
                                 {item["id"]: item["weight"] for item in behaviors})
                self.assertEqual(contract["forbidden"],
                                 {item["id"]: item["penalty"] for item in forbidden})
                for marker in contract["answer_markers"]:
                    self.assertIn(marker, reference["answer"], f"{case['case_id']} 参考答案缺少 {marker}")
                for marker in contract["input_markers"]:
                    self.assertIn(marker, case["input"], f"{case['case_id']} 输入缺少关键事实 {marker}")
        self.assertFalse(datasets["optimization"] & datasets["holdout"], "优化集与留出集必须互斥")

    def test_business_entry_has_no_acceptance_logic_and_does_not_import_tests(self):
        source = (SAMPLE_DIR / "main.py").read_text(encoding="utf-8")
        tree = ast.parse(source)
        for node in ast.walk(tree):
            self.assertNotIsInstance(node, ast.Assert, msg="业务入口 main.py 不得包含 assert")
        imported: list[str] = []
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.extend(alias.name for alias in node.names)
            elif isinstance(node, ast.ImportFrom):
                imported.append(node.module or "")
        for name in imported:
            root = name.split(".", 1)[0]
            self.assertNotIn(root, {"unittest", "tests", "test"}, f"main.py 不得导入测试模块: {name}")
            self.assertFalse(root.startswith("test_"), f"main.py 不得导入测试模块: {name}")
        self.assertNotIn("test_harness_rsi", source)
        self.assertNotIn("holdout", source.lower(), "留出集只由 test.py 使用，不进入业务入口")

    def test_hundred_point_display_converts_raw_scores(self):
        self.assertEqual(45.0, hundred_point_score(0.45))
        self.assertEqual(48.33, hundred_point_score(0.4833))
        self.assertEqual(100.0, hundred_point_score(1.0))
        self.assertEqual(0.0, hundred_point_score(0))
        self.assertEqual({"baseline": 45.0, "final": 50.0},
                         hundred_point_scores({"baseline": 0.45, "final": 0.5}))
        with self.assertRaisesRegex(ValueError, "0~1"):
            hundred_point_score(45.0)
        with self.assertRaisesRegex(ValueError, "0~1"):
            hundred_point_score(-0.1)

    def test_materials_have_owners_evidence_and_signoff(self):
        for name in MATERIAL_NAMES:
            files = sorted((SAMPLE / "materials").glob(f"{name}.*"))
            self.assertEqual(2, len(files), f"{name} 必须同时有模板和完整示例")
            for path in files:
                content = read_yaml(path) if path.suffix in {".json", ".yaml"} else path.read_text(
                    encoding="utf-8")
                if isinstance(content, str):
                    for marker in ("责任人", "输入", "输出", "签收", "证据"):
                        self.assertIn(marker, content, f"{path.name} 缺少 {marker}")
                else:
                    keys = set(nested_keys(content))
                    for key in ("owner", "inputs", "outputs", "sign_off", "evidence"):
                        self.assertIn(key, keys, f"{path.name} 缺少 {key}")
            self.assertNotEqual(files[0].read_text(encoding="utf-8"),
                                files[1].read_text(encoding="utf-8"))


def nested_keys(value):
    if isinstance(value, dict):
        for key, item in value.items():
            yield str(key)
            yield from nested_keys(item)
    elif isinstance(value, list):
        for item in value:
            yield from nested_keys(item)


# ---------------------------------------------------------------------------
# 验收规则单元测试（离线，不调用模型；用夹具验证验收逻辑本身）
# ---------------------------------------------------------------------------


class HarnessRsiContractTest(unittest.TestCase):
    materialize_runtime_refs = staticmethod(lambda: None)

    @classmethod
    def setUpClass(cls):
        cls.materialize_runtime_refs = staticmethod(load_business().materialize_runtime_refs)

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
            self.assertFalse(any("holdout" in str(path).lower() for path in request.dataset_files))
            self.assertEqual("contract-run", request.task_id)
            self.assertFalse(request.resume)
            self.assertEqual(str(Path(temporary).resolve() / "contract-run"), request.output_dir)
            self.assertEqual("single_harness", config.evaluator.backend)
            self.assertEqual("llm_as_judge", config.evaluator.evaluation_method)
            self.assertIs(False, config.evaluator.judge_baseline_comparison)
            self.assertTrue(Path(config.model_configs.evaluation).is_absolute())
            refs = read_yaml(Path(request.harness_refs_path))
            self.assertEqual(str(SOURCE_PACKAGE.resolve()), refs["harness_refs"]["solver"])
            with self.assertRaises(FileExistsError):
                prepare("contract-run", runs_dir=Path(temporary))
            Path(request.output_dir, "single_harness_state.yaml").write_text(
                "status: running\n", encoding="utf-8")
            _, resumed = prepare("contract-run", resume=True, runs_dir=Path(temporary))
            self.assertTrue(resumed.resume)

    def test_data_loader_batch_covers_all_optimization_cases(self):
        config = read_yaml(SAMPLE / "config/rsi.yaml")
        cases = read_json(OPTIMIZATION_DATA)["cases"]
        self.assertEqual(len(cases), config["data_loader"]["batch_size"],
                         "单批必须覆盖全部冻结优化案例")
        self.assertEqual(["prompt", "skill"], config["member_optimizer"]["action_group_configs"])

    def test_runtime_refs_use_absolute_package_path_without_mutating_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary) / "run"
            run_dir.mkdir()
            original = SOURCE_REFS.read_bytes()
            runtime_refs = self.materialize_runtime_refs(SOURCE_REFS, run_dir)
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

    def make_package(self, root: Path) -> Path:
        package = root / "credit_review"
        (package / "prompt_sections").mkdir(parents=True)
        (package / "skills" / "pre_loan_review").mkdir(parents=True)
        (package / "manifest.json").write_text(json.dumps({
            "package_type": "plugin", "id": "credit_review",
            "prompt_sections": [{"file": "prompt_sections/files/credit_review.md"}],
            "skills": [{"dir": "./skills/pre_loan_review", "mode": "all"}],
            "metadata": {"policy_version": "DEMO-CREDIT-2026.1"},
        }), encoding="utf-8")
        (package / "prompt_sections" / "files").mkdir(parents=True)
        (package / "prompt_sections" / "files" / "credit_review.md").write_text("原提示", encoding="utf-8")
        (package / "skills" / "pre_loan_review" / "SKILL.md").write_text("原技能", encoding="utf-8")
        return package

    def test_allows_prompt_skill_changes_and_empty_generated_registries(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = self.make_package(Path(temporary) / "source")
            final = self.make_package(Path(temporary) / "final")
            (final / "prompt_sections" / "files" / "credit_review.md").write_text("新提示", encoding="utf-8")
            skill = final / "skills" / "pre_loan_review"
            (skill / "SKILL.md").write_text("新技能", encoding="utf-8")
            (skill / "references").mkdir()
            (skill / "references" / "policy-demo.md").write_text("新参考", encoding="utf-8")
            for group in ("tools", "rails"):
                (final / group).mkdir()
                (final / group / f"{group}.yaml").write_text(f"{group}: []\n", encoding="utf-8")
            manifest = read_json(final / "manifest.json")
            manifest.update(tools=[], rails=[])
            (final / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            changed = assert_prompt_skill_only(source, final)
            self.assertIn("prompt_sections/files/credit_review.md", changed)
            self.assertIn("skills/pre_loan_review/SKILL.md", changed)
            self.assertIn("skills/pre_loan_review/references/policy-demo.md", changed)

    def test_rejects_non_prompt_skill_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = self.make_package(Path(temporary) / "source")
            final = self.make_package(Path(temporary) / "final")
            (final / "tools").mkdir()
            (final / "tools" / "credit.py").write_text("raise RuntimeError", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "tools/credit.py"):
                assert_prompt_skill_only(source, final)

    # -- 评测证据夹具 -------------------------------------------------------

    def write_evaluation(
            self,
            root: Path,
            case_ids=("case-a",),
            *,
            scores: dict[str, float] | None = None,
            execution_status: str = "passed",
            score_mode: str = "continuous",
            forbidden: dict[str, list[str]] | None = None,
    ) -> Path:
        root.mkdir(parents=True, exist_ok=True)
        scores = dict(scores or {})
        forbidden = dict(forbidden or {})
        cases = []
        for case_id in case_ids:
            score = float(scores.get(case_id, 0.5))
            result_path = root / "cases" / case_id / "result.json"
            result_path.parent.mkdir(parents=True, exist_ok=True)
            hits = [
                {"id": hit_id, "triggered": True, "reason": "fixture", "evidence": "fixture",
                 "penalty": 1.0, "description": "fixture"}
                for hit_id in forbidden.get(case_id, [])
            ]
            result_path.write_text(json.dumps({
                "case_id": case_id,
                "status": "passed" if execution_status == "passed" else "failed",
                "execution_status": execution_status,
                "score": score,
                "evaluation": {
                    "method": "llm_as_judge",
                    "passed": execution_status == "passed" and score >= 0.6,
                    "reason": "fixture",
                    "metadata": {"score_mode": score_mode,
                                 "parsed": {"forbidden_hits": hits}},
                },
                "result": {"output": "\n".join(OUTPUT_HEADINGS)},
                "error": "" if execution_status == "passed" else "connection failed",
            }), encoding="utf-8")
            cases.append({
                "case_id": case_id,
                "status": "passed" if execution_status == "passed" else "failed",
                "score": score,
                "result_path": str(result_path),
                "metadata": {"execution_status": execution_status,
                             "evaluation_method": "llm_as_judge",
                             "score_mode": score_mode},
            })
        summary_path = root / "summary.json"
        average = sum(float(scores.get(case_id, 0.5)) for case_id in case_ids) / len(case_ids)
        summary_path.write_text(json.dumps({
            "total_cases": len(case_ids), "average_score": average,
            "evaluation_method": "llm_as_judge",
        }), encoding="utf-8")
        eval_ref = root / "eval_ref.yaml"
        eval_ref.write_text(yaml.safe_dump(
            {"summary_path": str(summary_path), "cases": cases}), encoding="utf-8")
        return eval_ref

    def write_run_files(self, root: Path, shared: dict) -> object:
        state_path = root / "single_harness_state.yaml"
        report_path = root / "single_harness_report.yaml"
        state_path.write_text(yaml.safe_dump(shared), encoding="utf-8")
        report_path.write_text(yaml.safe_dump(shared), encoding="utf-8")
        return type("Result", (), {"state_path": str(state_path), "report_path": str(report_path)})()

    # -- 证据校验测试 -------------------------------------------------------

    def test_evaluation_requires_complete_real_execution_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            valid = self.write_evaluation(root / "valid", ("a", "b"),
                                          scores={"a": 0.5, "b": 1.0})
            summary = validate_evaluation(str(valid), {"a", "b"})
            self.assertAlmostEqual(0.75, summary["average_score"], places=9)
            valid_ids = evaluation_scores(str(valid), {"a", "b"})
            self.assertEqual({"a": 0.5, "b": 1.0}, valid_ids)

            failed = self.write_evaluation(root / "failed", ("a",), execution_status="failed")
            with self.assertRaisesRegex(ValueError, "execution_status"):
                validate_evaluation(str(failed), {"a"})

            binary = self.write_evaluation(root / "binary", ("a",), score_mode="binary")
            with self.assertRaisesRegex(ValueError, "continuous"):
                validate_evaluation(str(binary), {"a"})

            missing = self.write_evaluation(root / "missing", ("a",))
            (root / "missing" / "cases" / "a" / "result.json").unlink()
            with self.assertRaisesRegex(ValueError, "result.json"):
                validate_evaluation(str(missing), {"a"})

            with self.assertRaisesRegex(ValueError, "case_id"):
                validate_evaluation(str(valid), {"a", "c"})

    def test_stage_improvement_requires_two_challenge_cases_and_protected_retention(self):
        protected = {"protect-1", "protect-2"}
        challenge = {"challenge-1", "challenge-2", "challenge-3", "challenge-4"}
        expected = protected | challenge
        baseline_scores = {**{case_id: 0.3 for case_id in challenge},
                           **{case_id: 0.9 for case_id in protected}}
        improved_scores = {
            "challenge-1": 0.6, "challenge-2": 0.5,
            "challenge-3": 0.4, "challenge-4": 0.35,
            "protect-1": 0.86, "protect-2": 0.9,
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            baseline = self.write_evaluation(root / "baseline", tuple(expected),
                                             scores=baseline_scores)
            full = self.write_evaluation(root / "full", tuple(expected), scores=improved_scores)
            deltas = validate_stage_improvement(
                baseline_eval_ref=baseline, full_eval_ref=full,
                expected_case_ids=expected, challenge_case_ids=challenge,
                protected_case_ids=protected,
            )
            self.assertAlmostEqual(0.3, deltas["challenge-1"], places=9)

            weak_scores = dict(improved_scores)
            weak_scores["challenge-2"] = 0.35
            weak = self.write_evaluation(root / "weak", tuple(expected), scores=weak_scores)
            with self.assertRaisesRegex(ValueError, "挑战案"):
                validate_stage_improvement(
                    baseline_eval_ref=baseline, full_eval_ref=weak,
                    expected_case_ids=expected, challenge_case_ids=challenge,
                    protected_case_ids=protected,
                )

            regressed_scores = dict(improved_scores)
            regressed_scores["protect-2"] = 0.8
            regressed = self.write_evaluation(root / "regressed", tuple(expected),
                                              scores=regressed_scores)
            with self.assertRaisesRegex(ValueError, "保护案"):
                validate_stage_improvement(
                    baseline_eval_ref=baseline, full_eval_ref=regressed,
                    expected_case_ids=expected, challenge_case_ids=challenge,
                    protected_case_ids=protected,
                )

    def test_stage_improvement_rejects_new_forbidden_triggers(self):
        protected = {"protect-1"}
        challenge = {"challenge-1", "challenge-2"}
        expected = protected | challenge
        baseline_scores = {"protect-1": 0.9, "challenge-1": 0.2, "challenge-2": 0.2}
        full_scores = {"protect-1": 0.9, "challenge-1": 0.6, "challenge-2": 0.6}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            baseline = self.write_evaluation(
                root / "baseline", tuple(expected), scores=baseline_scores,
                forbidden={"challenge-1": ["raw_period_mix"]})
            clean = self.write_evaluation(root / "clean", tuple(expected), scores=full_scores)
            validate_stage_improvement(
                baseline_eval_ref=baseline, full_eval_ref=clean,
                expected_case_ids=expected, challenge_case_ids=challenge,
                protected_case_ids=protected,
            )
            dirty = self.write_evaluation(
                root / "dirty", tuple(expected), scores=full_scores,
                forbidden={"challenge-1": ["raw_period_mix", "invented_facts"]})
            with self.assertRaisesRegex(ValueError, "新增 forbidden"):
                validate_stage_improvement(
                    baseline_eval_ref=baseline, full_eval_ref=dirty,
                    expected_case_ids=expected, challenge_case_ids=challenge,
                    protected_case_ids=protected,
                )

    def test_no_improvement_run_is_completed_but_not_published(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_refs = root / "source.yaml"
            source_refs.write_text("harness_refs: {solver: credit_review}\n", encoding="utf-8")
            baseline = self.write_evaluation(root / "baseline", ("a", "b"))
            shared = {
                "status": "completed", "baseline_score": 0.5, "best_score": 0.5,
                "baseline_eval_ref_path": str(baseline), "best_eval_ref_path": str(baseline),
                "best_harness_refs_path": str(source_refs),
                "current_harness_refs_path": str(source_refs),
                "published_harness_refs_path": "",
                "publication_status": "not_published_no_improvement",
                "candidate_gates": [], "accepted_candidate_count": 0,
            }
            result = self.write_run_files(root, shared)
            report, final_refs = validate_run_evidence(result, {"a", "b"}, str(source_refs))
            self.assertEqual("completed", report["status"])
            self.assertEqual(str(source_refs), final_refs)

    def test_published_run_requires_business_stage_improvement(self):
        expected = {"case-a", "protect-a", "challenge-1", "challenge-2"}
        baseline_scores = {"case-a": 0.4, "protect-a": 0.9,
                           "challenge-1": 0.2, "challenge-2": 0.2}
        full_scores = {"case-a": 0.5, "protect-a": 0.88,
                       "challenge-1": 0.6, "challenge-2": 0.55}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            baseline = self.write_evaluation(root / "baseline", tuple(expected),
                                             scores=baseline_scores)
            full = self.write_evaluation(root / "full", tuple(expected), scores=full_scores)
            candidate_package = root / "candidate" / "credit_review"
            shutil.copytree(SOURCE_PACKAGE, candidate_package)
            (candidate_package / "skills" / SKILL_DIR_NAME / "SKILL.md").write_text(
                "# 候选技能\n", encoding="utf-8")
            candidate_refs = write_single_package_refs(
                root / "candidate_refs.yaml", candidate_package)
            published_refs = write_single_package_refs(
                root / "published_refs.yaml", candidate_package)
            proof = {"qualified": True, "scope": "target_cases",
                     "target_case_ids": sorted(expected)}
            gate = {
                "accepted": True, "status": "accepted",
                "candidate_harness_refs_path": str(candidate_refs),
                "candidate_eval_ref_path": str(full),
                "full_eval_ref_path": str(full),
                "improvement_proof": proof,
                "target_case_ids": sorted(expected),
                "full_engineering_passed": True,
                "full_checks": {"accepted": True},
            }
            shared = {
                "status": "completed",
                "baseline_score": sum(baseline_scores.values()) / len(baseline_scores),
                "best_score": sum(full_scores.values()) / len(full_scores),
                "baseline_eval_ref_path": str(baseline),
                "best_eval_ref_path": str(full),
                "best_harness_refs_path": str(candidate_refs),
                "current_harness_refs_path": str(candidate_refs),
                "published_harness_refs_path": str(published_refs),
                "publication_status": "published",
                "candidate_gates": [gate],
                "accepted_candidate_count": 1,
                "best_improvement_proof": proof,
            }
            result = self.write_run_files(root, shared)
            report, final_refs = validate_run_evidence(
                result, expected, str(candidate_refs))
            self.assertEqual("published", report["publication_status"])
            self.assertEqual(str(published_refs), final_refs)

    def test_final_artifact_requires_complete_skill_directory(self):
        package, plugin = validate_final_artifact(SOURCE_REFS)
        self.assertEqual(SOURCE_PACKAGE, package)
        self.assertTrue(plugin.prompt_sections)
        self.assertTrue(plugin.skills)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            broken = root / "credit_review"
            shutil.copytree(SOURCE_PACKAGE, broken)
            (broken / "skills" / SKILL_DIR_NAME / "references" / "output-format.md").unlink()
            broken_refs = write_single_package_refs(root / "broken_refs.yaml", broken)
            with self.assertRaisesRegex(ValueError, "output-format.md"):
                validate_final_artifact(broken_refs)

            invalid_script = root / "credit_review_bad_script"
            shutil.copytree(SOURCE_PACKAGE, invalid_script)
            (invalid_script / "skills" / SKILL_DIR_NAME / "scripts" / "dti.py").write_text(
                "def broken(:\n", encoding="utf-8")
            invalid_refs = write_single_package_refs(
                root / "invalid_refs.yaml", invalid_script)
            with self.assertRaisesRegex(ValueError, "编译"):
                validate_final_artifact(invalid_refs)


# ---------------------------------------------------------------------------
# 真实模型集成验收（未设置环境变量时明确跳过）
# ---------------------------------------------------------------------------


class HarnessRsiRealModelTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        missing = [variable for variable in ("API_BASE", "API_KEY", "MODEL_NAME")
                   if not os.getenv(variable, "").strip()]
        if missing:
            self.skipTest(
                f"真实模型集成测试需要环境变量 {'/'.join(missing)}；未设置时明确跳过，"
                "请配置真实模型服务后运行")

    async def test_real_rsi_reload_smoke_and_independent_holdout(self):
        import openjiuwen
        from openjiuwen.rsi import TeamEvaluator, load_cases

        self.assertEqual("0.1.20", openjiuwen.__version__)
        business = load_business()
        cases = load_cases([str(OPTIMIZATION_DATA)])
        holdout = load_cases([str(HOLDOUT_DATA)])
        case_ids = {case["case_id"] for case in cases}
        holdout_ids = {case["case_id"] for case in holdout}
        self.assertEqual(set(OPTIMIZATION_CASE_IDS), case_ids)
        self.assertEqual(set(HOLDOUT_CASE_IDS), holdout_ids)
        self.assertFalse(case_ids & holdout_ids)

        existing_run = os.getenv("RSI_ACCEPT_RUN_DIR", "").strip()
        if existing_run:
            run_dir = Path(existing_run).expanduser().resolve()
            task_id = run_dir.name
            state_path = run_dir / "single_harness_state.yaml"
            report_path = run_dir / "single_harness_report.yaml"
            self.assertTrue(state_path.is_file(), f"既有运行缺少 state: {state_path}")
            self.assertTrue(report_path.is_file(), f"既有运行缺少 report: {report_path}")
            print(f"复验既有真实运行目录：{run_dir}", flush=True)
            result = type("RunResult", (), {
                "state_path": str(state_path), "report_path": str(report_path),
            })()
        else:
            task_id = datetime.now(UTC).strftime("credit-rsi-ut-%Y%m%dT%H%M%S%fZ")
            run_dir = SAMPLE_DIR / "runs" / task_id
            print(f"真实模型验收目录：{run_dir}", flush=True)
            result = await business.run(task_id)
        source_refs = str(run_dir / "source_harness_refs.yaml")
        report, final_refs = validate_run_evidence(result, case_ids, source_refs)
        package, _plugin = validate_final_artifact(final_refs)
        changed = assert_prompt_skill_only(SOURCE_PACKAGE, package)

        config, _request = business.prepare_run(task_id, resume=True, runs_dir=run_dir.parent)
        scores: dict[str, float] = {}
        for name, refs in (("baseline", source_refs), ("final", final_refs)):
            evaluation = await TeamEvaluator(config.evaluator).evaluate_batch(
                cases=holdout, team_skill_ref_path="", harness_refs_path=refs,
                output_dir=str(run_dir / "holdout" / name), case_concurrency=1,
            )
            scores[name] = float(validate_evaluation(evaluation, holdout_ids)["average_score"])
            if name == "final":
                for case in load_mapping(evaluation)["cases"]:
                    output = read_json(case["result_path"])["result"]
                    text = json.dumps(output, ensure_ascii=False)
                    for heading in OUTPUT_HEADINGS:
                        self.assertIn(heading, text, f"{case['case_id']} 缺少贷前审查结构")

        if os.getenv("REQUIRE_IMPROVEMENT") == "1":
            self.assertEqual("published", report["publication_status"],
                             "REQUIRE_IMPROVEMENT=1 要求官方 published")
            self.assertGreater(report["best_score"], report["baseline_score"],
                               "REQUIRE_IMPROVEMENT=1 要求最佳分高于基线")

        print(json.dumps({
            "task_id": task_id,
            "report_path": result.report_path,
            "publication_status": report["publication_status"],
            "score_scale": 100,
            "baseline_score": hundred_point_score(report["baseline_score"]),
            "best_score": hundred_point_score(report["best_score"]),
            "accepted_candidate_count": report.get("accepted_candidate_count", 0),
            "changed_prompt_skill_files": changed,
            "final_package": str(package),
            "holdout_scores": hundred_point_scores(scores),
        }, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
