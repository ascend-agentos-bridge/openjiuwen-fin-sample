"""样例 03 · Prompt 模板与组装（真实 openjiuwen API）—— 信贷审批意见生成。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API:
- PromptTemplate(content=..., placeholder_prefix/suffix): 变量化模板
- .format(keywords={...}): 变量替换, 返回新模板; 缺失变量保留占位符原样
- .to_messages(): 渲染结果转 BaseMessage 列表
- PromptAssembler(模板字符串): 多段 Prompt 组装, prompt_assemble(**kwargs) 填值;
  缺失占位符保留 {{key}} 原样

运行: python main.py   （不需要 LLM）
"""
from openjiuwen.core.foundation.prompt import PromptTemplate
from openjiuwen.core.foundation.prompt.assemble.assembler import PromptAssembler


def main():
    # ---- 1. PromptTemplate: 审批意见材料模板 ----
    tpl = PromptTemplate(
        name="loan_material",
        content="客户{name}（信用等级{credit_level}）申请{product}，金额{amount}万元，期限{term_months}个月。",
        placeholder_prefix="{", placeholder_suffix="}")
    print("=== 模板渲染 format(keywords) ===")
    rendered = tpl.format(keywords={"name": "张伟", "credit_level": "AA",
                                    "product": "个人消费贷", "amount": 30, "term_months": 36})
    print(rendered.content)
    msgs = rendered.to_messages()
    print(f"to_messages -> {type(msgs[0]).__name__}(role={msgs[0].role}): {msgs[0].content[:36]}...")

    # ---- 2. 缺失变量的真实行为: 占位符保留原样（不报错） ----
    partial = tpl.format(keywords={"name": "张伟"})
    print(f"\n=== 缺变量渲染（真实行为: 保留占位符） ===\n{partial.content}")

    # ---- 3. PromptAssembler: 五段式组装（模板含 {{占位符}}, kwargs 填值） ----
    customer_profile = "{'customer_id': 'C1001', 'name': '张伟', 'credit_level': 'AA', 'overdue': False}"
    policy_text = "《个人消费贷管理办法》第 12 条：信用等级 B 及以上可申请，单户上限 50 万元。"

    assembler = PromptAssembler(
        "## 角色\n你是九州银行信贷审批助理，依据材料与政策发表意见。"
        "\n\n## 任务\n出具：同意/不同意/需人工复审，并给出两条理由。"
        "\n\n## 客户资料(核心系统)\n{{profile}}"
        "\n\n## 相关政策(知识库检索)\n{{policy}}"
        "\n\n## 用户输入\n{{material}}")
    final_prompt = assembler.prompt_assemble(
        profile=customer_profile, policy=policy_text, material=rendered.content)
    assert isinstance(final_prompt, str)
    print("\n=== 最终组装 Prompt ===")
    print(final_prompt)

    # ---- 4. 缺失占位符: 保留 {{key}} 原样 ----
    partial2 = PromptAssembler("角色固定。\n\n## 补充材料\n{{extra}}").prompt_assemble()
    print(f"\n=== 缺占位符（真实行为: 保留原样） ===\n{partial2}")

    # ---- 5. 验收断言 ----
    assert "张伟" in rendered.content and "36" in rendered.content
    assert "{credit_level}" in partial.content, "缺变量应保留占位符"
    assert msgs[0].role == "user"
    for section in ("## 角色", "## 任务", "## 客户资料", "## 相关政策", "## 用户输入"):
        assert section in final_prompt, f"缺少段落 {section}"
    assert "个人消费贷管理办法" in final_prompt and "C1001" in final_prompt
    assert "{{extra}}" in partial2
    print("\nSUCCESS: 模板渲染 + 缺变量行为 + 五段式组装 验收通过")


if __name__ == "__main__":
    main()
