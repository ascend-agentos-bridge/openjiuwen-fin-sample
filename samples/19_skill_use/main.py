"""样例 19 · Agent Skill（真实 openjiuwen API）—— 给 Agent 装配「贷前审查」技能。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API:
- SysOperationCard(mode=LOCAL) + Runner.resource_mgr.add_sys_operation: 文件操作底座
- get_sys_op_tool_cards("fs", "read_file"): 挂载 read_file —— 技能提示注入的前提
- agent.register_skill(skills_dir): 注册技能。skills/ 下每个含 SKILL.md 的子目录
  注册为一个技能（技能名 = 子目录名，description 取自 SKILL.md 的 YAML frontmatter）
- 运行时框架把技能清单（名称/描述/目录）注入系统提示，指示模型用 read_file
  读取 SKILL.md 并按其流程执行 —— 即「渐进式技能装载」

运行前提:
- openjiuwen==0.1.16（pip install -U openjiuwen）
- OpenAI 兼容 LLM 网关, 环境变量 API_BASE / API_KEY / MODEL_NAME / MODEL_PROVIDER
  （缺省指向本地 http://127.0.0.1:1234 的 google/gemma-4-e2b）

运行: python main.py
"""
import asyncio
import os
from pathlib import Path

from openjiuwen.core.foundation.tool import tool
from openjiuwen.core.runner import Runner
from openjiuwen.core.single_agent import AgentCard, ReActAgent, ReActAgentConfig
from openjiuwen.core.sys_operation import (LocalWorkConfig, OperationMode,
                                           SysOperationCard)

# ---- 0. LLM 配置: 与官方 examples 相同的环境变量约定 ----
API_BASE = os.getenv("API_BASE", "http://127.0.0.1:1234/v1")
API_KEY = os.getenv("API_KEY", "lm-studio")
MODEL_NAME = os.getenv("MODEL_NAME", "google/gemma-4-e2b")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")

# 技能目录: skills/ 下每个含 SKILL.md 的子目录 = 一个技能
SKILLS_DIR = Path(__file__).resolve().parent / "skills"


# ---- 1. 业务工具: 客户档案查询（技能正文要求模型先查档再撰写） ----
@tool(description="按客户号查询信贷客户档案：姓名、信用等级、贷款余额(万元)、是否逾期")
def query_customer(customer_id: str):
    db = {
        "C1001": {"name": "张伟", "credit_level": "AA", "loan_balance_wan": 58.0, "overdue": False},
        "C1002": {"name": "李娜", "credit_level": "B", "loan_balance_wan": 120.0, "overdue": True},
    }
    return db.get(customer_id, {"error": f"客户 {customer_id} 不存在"})


async def main():
    # ---- 2. SysOperation: 技能注册与 read_file 走这套文件操作底座 ----
    sysop_card = SysOperationCard(
        id="skill19_fs_ops", name="skill19_fs_ops", description="技能文件读取",
        mode=OperationMode.LOCAL,
        work_config=LocalWorkConfig(work_dir=None))
    Runner.resource_mgr.add_sys_operation(sysop_card)

    # ---- 3. Agent 构建: sys_operation_id 必须挂上, 否则注册技能时找不到 fs ----
    cfg = (ReActAgentConfig()
           .configure_model_client(provider=MODEL_PROVIDER, api_key=API_KEY,
                                   api_base=API_BASE, model_name=MODEL_NAME,
                                   verify_ssl=False)
           .configure_prompt_template([{
               "role": "system",
               "content": "你是银行信贷审查助手。开始任务前，先用 read_file 查看已装配的"
                          "技能文档（SKILL.md），并严格按技能规定的工作流程与输出模板执行；"
                          "所有数据必须来自工具查询结果，禁止编造。",
           }])
           .configure_max_iterations(8))
    cfg.sys_operation_id = sysop_card.id
    # 本地小模型推理慢, 默认 60s 会超时（同样例 01 的坑）
    cfg.model_client_config.timeout = 300
    agent = ReActAgent(card=AgentCard(id="credit_skill_agent",
                                      description="带技能的信贷审查助手")).configure(cfg)

    # ---- 4. 挂载 read_file 工具: 框架注入的技能提示要求用 read_file 读 SKILL.md,
    #         不挂载时框架只在日志告警, 模型拿不到技能正文 ----
    read_file_card = Runner.resource_mgr.get_sys_op_tool_cards(
        sys_operation_id=sysop_card.id, operation_name="fs", tool_name="read_file")
    agent.ability_manager.add(read_file_card)

    # ---- 5. 注册业务工具 + 注册技能（一次注册 skills/ 下全部技能目录） ----
    Runner.resource_mgr.add_tool(query_customer)
    agent.ability_manager.add(query_customer.card)
    await agent.register_skill(str(SKILLS_DIR))

    # ---- 6. 运行: 提问里不出现"技能"二字, 看模型是否按技能描述自主匹配并遵循 ----
    result = await Runner.run_agent(
        agent=agent,
        inputs={"query": "客户 C1001 申请续贷，请出具贷前审查意见。",
                "conversation_id": "sample-19"})
    answer = result.get("output", "")
    print("=== 最终答案 ===")
    print(answer)

    # ---- 7. 程序化验收: 断言结构性事实 ----
    # 五段模板标题只存在于 SKILL.md —— 答案里出现, 即证明模型读了技能并按其执行
    assert answer and answer.strip(), "应产出非空最终答案"
    assert "【客户档案】" in answer and "【审批结论】" in answer, \
        f"答案未遵循 SKILL.md 的五段模板, 实际: {answer[:160]}"
    assert "58" in answer or "AA" in answer or "张伟" in answer, \
        f"答案应引用工具查询到的客户数据, 实际: {answer[:160]}"
    print("\nSUCCESS: register_skill 注册 + 技能提示注入 + 模型按 SKILL.md 流程执行, 验收通过")


if __name__ == "__main__":
    asyncio.run(main())
