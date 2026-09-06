# 样例 02 · 工具开发全家桶 —— Schema/校验/ServiceAPI/凭证头

> 使用**开源 openjiuwen Agent Core 真实 API**（`openjiuwen==0.1.16`）。对应特性清单 §6.1-§6.4。

## 场景

为信贷 Agent 准备工具能力：`@tool` 月供计算器（本地函数工具）、授信系统 HTTP API 包装成 `RestfulApi` 工具（凭证走请求头）。

## 运行

```bash
python main.py    # 不需要 LLM, 纯工具层
```

## 验收方式

1. 打印 `@tool` 自动生成的 JSON Schema（`number/integer` 类型、`required`、`additionalProperties: false`）；
2. `invoke({"principal":100,"annual_rate":4.9,"years":30})` 返回 **5307.27**（100万/4.9%/30年等额本息月供）；
3. 缺参/多参抛 `ValidationError: [189001] validate data with schema failed`（真实行为是**抛异常**，不是返回错误文本）；
4. `RestfulApi.invoke` 返回 `{'code': 200, 'data': {...risk_tag...}, 'reason': 'OK'}`；
5. 最后一行 `SUCCESS: ...`，退出码 0。

实测输出（节选）：

```text
=== 正常调用 ===
月供: 5307.27 元

=== 参数校验（真实行为: 抛 ValidationError） ===
缺参数: ValidationError: [189001] validate data with schema failed, error='2 validation errors for calcul...
多参数: ValidationError: [189001] validate data with schema failed, error='1 validation error for calcula...

=== ServiceAPI 工具（headers 携带凭证） ===
RestfulApi -> {'code': 200, 'data': {'customer_id': 'C1001', 'risk_tag': '正常', 'debt_ratio': 0.42}, 'url': 'http://127.0.0.1:32735/credit?q=C1001', ..., 'message': 'success'}

SUCCESS: Schema 提取/invoke 校验/ServiceAPI+凭证头 全部验收通过
```

## 代码讲解（真实 API）

| 关键点 | API | 说明 |
|---|---|---|
| 工具定义 | `@tool(description=...)` | `LocalFunction`；schema 从类型注解提取 |
| 查看发给模型的描述 | `f.card.input_params` / `f.card.name` | `ToolCard` 是 pydantic 模型 |
| 直接调用 | `await f.invoke({...})` | pydantic 按 schema 校验入参 |
| HTTP API 工具 | `RestfulApi(card=RestfulApiCard(...))` | `url/headers/method/input_params` 声明式配置 |
| 凭证 | `RestfulApiCard(headers={"X-API-Key": ...})` | 凭证在**请求头**里，不进 `input_params`，模型不可见 |

## 开发者注意（真实踩坑）

- **参数校验失败是抛异常**（`ValidationError[189001]`），不是返回错误文本——Agent 层会把它转成工具观察回传给模型；直接调工具时自己 try/except；
- **SSRF 防护默认开启**：`RestfulApiCard` 的 URL 指向 `127.0.0.1`/内网 IP 会被拒（`illegal ip address`）。本地/内网服务需设环境变量 `SSRF_PROTECT_ENABLED=false`（框架官方开关，生产放行内网依赖同样用它）；
- 更完整的认证策略（SSL/HeaderQuery/自定义 AuthStrategy）在 `openjiuwen.core.foundation.tool.auth`，本样例用 headers 直传演示最小形态；
- 工具 description 直接决定模型选型准确率，按"什么时候该用我"写。
