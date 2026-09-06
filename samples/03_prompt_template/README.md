# 样例 03 · Prompt 模板与组装 —— 信贷审批意见生成

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`）。对应特性清单 §5.1/§5.2。

## 场景

审批助理的五段式 Prompt：角色 + 任务 + 客户资料（核心系统）+ 相关政策（知识库检索）+ 用户输入。

## 运行

```bash
python main.py    # 不需要 LLM
```

## 验收方式

1. `PromptTemplate.format(keywords={...})` 渲染出申请材料（含 `张伟`/`36`），`.to_messages()` 得到 `UserMessage`；
2. 缺变量渲染**保留占位符原样**（`{credit_level}` 等，不报错）；
3. `PromptAssembler` 组装的五段全部出现，政策与客户资料就位；
4. 缺占位符时 `{{extra}}` 保留原样；
5. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（节选）：

```text
=== 模板渲染 format(keywords) ===
客户张伟（信用等级AA）申请个人消费贷，金额30万元，期限36个月。
to_messages -> UserMessage(role=user): 客户张伟（信用等级AA）...

=== 缺变量渲染（真实行为: 保留占位符） ===
客户张伟（信用等级{credit_level}）申请{product}，金额{amount}万元，期限{term_months}个月。

=== 最终组装 Prompt ===
## 角色
...
## 相关政策(知识库检索)
《个人消费贷管理办法》第 12 条：...

SUCCESS: 模板渲染 + 缺变量行为 + 五段式组装 验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 模板 | `PromptTemplate(name=..., content=..., placeholder_prefix="{", placeholder_suffix="}")` | pydantic 模型 |
| 渲染 | `tpl.format(keywords={...})` | 返回**新模板**，原模板不变 |
| 转消息 | `tpl.to_messages()` | `List[BaseMessage]`，默认 user 角色 |
| 组装 | `PromptAssembler(模板字符串)` + `prompt_assemble(**kwargs)` | 模板含 `{{占位符}}`（默认 `{{}}` 前后缀），kwargs 填值，返回 str |

## 开发者注意（真实踩坑）

- **缺变量不报错**：`format`/`prompt_assemble` 对缺失变量保留占位符原样（`{x}`/`{{x}}`）——与很多框架的 fail-fast 不同。上线前建议自己断言最终 prompt 里不含 `{{`；
- `PromptAssembler` 的**占位符默认前缀是 `{{ }}`**，与 `PromptTemplate` 默认 `{ }` 不同（构造时都可自定义）；
- `assembler.input_keys` 是模板里声明的占位符键，可用于运行时校验必填变量；
- 传给 `PromptAssembler(**variables)` 的变量必须是 `Variable` 对象（TextableVariable/DictableVariable），用于自定义渲染；普通填值直接走 `prompt_assemble(**kwargs)` 即可。
