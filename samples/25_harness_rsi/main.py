"""信贷 Harness 演进：配置官方 RSI、发起/恢复任务、展示结果。"""

import asyncio
from datetime import UTC, datetime
import os
from pathlib import Path
import re
import shutil

import yaml


SAMPLE_DIR = Path(__file__).resolve().parent
SOURCE_REFS = SAMPLE_DIR / "harness/harness_refs.yaml"
OPTIMIZATION_DATA = SAMPLE_DIR / "data/optimization_cases.json"


def reset_member_workspace(runs_root: Path) -> None:
    """清理官方成员优化器的共享工作区，避免上个任务的 worktree 文件混入候选包。"""
    member_workspace = (runs_root / "mh").resolve()
    if not member_workspace.is_relative_to(runs_root) or not member_workspace.is_dir():
        return
    shutil.rmtree(member_workspace)


def materialize_runtime_refs(source_refs: Path, run_dir: Path) -> Path:
    """将包引用转成绝对路径，供官方执行器在独立工作目录中加载。"""
    refs = yaml.safe_load(source_refs.read_text(encoding="utf-8"))
    package = (source_refs.parent / refs["harness_refs"]["solver"]).resolve()
    target = run_dir / "source_harness_refs.yaml"
    target.write_text(yaml.safe_dump({"harness_refs": {"solver": str(package)}}), encoding="utf-8")
    return target


def prepare_run(task_id: str, *, resume: bool = False, runs_dir: Path | None = None):
    """准备本次任务的配置与官方请求，不执行模型调用。"""
    from openjiuwen.rsi import AutoCoordinatingHarnessConfig, IterativeSingleHarnessRequest

    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", task_id):
        raise ValueError("任务名需以字母或数字开头，只能包含字母、数字、点、下划线和连字符")
    runs_root = (runs_dir or SAMPLE_DIR / "runs").resolve()
    run_dir = runs_root / task_id
    if resume and not (run_dir / "single_harness_state.yaml").is_file():
        raise ValueError(f"没有可恢复的任务状态: {run_dir}")
    if not resume:
        run_dir.mkdir(parents=True, exist_ok=False)
        reset_member_workspace(runs_root)
    source_refs = run_dir / "source_harness_refs.yaml"
    if not resume:
        source_refs = materialize_runtime_refs(SOURCE_REFS, run_dir)

    config = yaml.safe_load((SAMPLE_DIR / "config/rsi.yaml").read_text(encoding="utf-8"))
    default_model = SAMPLE_DIR / "config/model.yaml"
    for role, variable in (
        ("evaluation", "RSI_EVALUATION_MODEL_CONFIG"),
        ("analysis", "RSI_ANALYSIS_MODEL_CONFIG"),
        ("member_optimization", "RSI_OPTIMIZATION_MODEL_CONFIG"),
    ):
        config["model_configs"][role] = str(Path(os.getenv(variable, str(default_model))).expanduser().resolve())
    config["evaluator"]["judge_model_config_ref"] = str(
        Path(os.getenv("RSI_JUDGE_MODEL_CONFIG", str(default_model))).expanduser().resolve())
    config["workspace_dir"] = str(run_dir / "workspace")
    return AutoCoordinatingHarnessConfig.from_dict(config), IterativeSingleHarnessRequest(
        dataset_files=[str(OPTIMIZATION_DATA)],
        harness_refs_path=str(source_refs),
        output_dir=str(run_dir),
        dataset_id="credit-review-optimization-v2",
        auto_full_baseline=True,
        task_id=task_id,
        max_iteration=1,
        resume=resume,
    )


async def run(task_id: str, *, resume: bool = False, runs_dir: Path | None = None):
    """交给官方编排器执行基线、归因、候选、回放与晋升。"""
    from openjiuwen.rsi import SingleHarnessIterativeOptimizationOrchestrator

    config, request = prepare_run(task_id, resume=resume, runs_dir=runs_dir)
    return await SingleHarnessIterativeOptimizationOrchestrator(config).run(request)


async def main() -> None:
    task_id = os.getenv("RSI_TASK_ID") or datetime.now(UTC).strftime("credit-rsi-%Y%m%dT%H%M%SZ")
    print(f"开始信贷 Harness 演进：{task_id}", flush=True)
    result = await run(task_id, resume=os.getenv("RSI_RESUME") == "1")
    report = yaml.safe_load(Path(result.report_path).read_text(encoding="utf-8"))
    print(f"发布状态：{report['publication_status']}")
    print(f"基线分数：{report['baseline_score'] * 100:.2f} 分；"
          f"最佳分数：{result.best_score * 100:.2f} 分（满分 100 分）")
    print(f"最佳 Harness：{result.best_harness_refs_path}")
    if result.published_harness_refs_path:
        print(f"发布包：{result.published_harness_refs_path}")
    print(f"任务状态：{result.state_path}")
    print(f"演进报告：{result.report_path}")


if __name__ == "__main__":
    asyncio.run(main())
