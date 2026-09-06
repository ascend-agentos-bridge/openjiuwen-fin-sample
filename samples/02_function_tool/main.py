"""样例 02 · 工具开发全家桶（真实 openjiuwen API）—— 信贷工具定义/Schema/校验/ServiceAPI。

使用开源 openjiuwen Agent Core（v0.1.16）真实 API:
- @tool 装饰器: 函数 → LocalFunction, JSON Schema 自动从签名提取
- LocalFunction.card: 查看将发给模型的 ToolCard（name/description/input_params）
- LocalFunction.invoke: 直接调用工具; 参数由 schema 校验（缺参/多参抛 ValidationError[189001]）
- RestfulApiCard + RestfulApi: 把现有 HTTP API 包装成工具
- headers 携带凭证: API Key 走请求头, 不进模型可见的 input_params

运行: python main.py
"""
import asyncio
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

# 框架默认开启 SSRF 防护(禁止访问内网/环回地址)。本样例的 mock 授信 API 跑在本机,
# 因此按框架提供的官方开关放行; 生产环境访问真实内网服务时同样需要此开关。
os.environ.setdefault("SSRF_PROTECT_ENABLED", "false")

from openjiuwen.core.foundation.tool import RestfulApi, RestfulApiCard, tool  # noqa: E402


# ---- 1. @tool: 业务函数 → 工具（等额本息月供计算） ----
@tool(description="等额本息月供计算器。principal=贷款本金(万元), annual_rate=年利率(如4.9表示4.9%), years=年限")
def calculate_monthly_payment(principal: float, annual_rate: float, years: int) -> float:
    r, n = annual_rate / 100 / 12, years * 12
    if r == 0:
        return round(principal * 10000 / n, 2)
    return round(principal * 10000 * r * (1 + r) ** n / ((1 + r) ** n - 1), 2)


async def main():
    # ---- 2. 查看 ToolCard: 这就是发给模型的工具描述 ----
    card = calculate_monthly_payment.card
    print("=== 工具 Schema（自动生成） ===")
    print(json.dumps(card.input_params, ensure_ascii=False, indent=1))

    # ---- 3. invoke 正常调用 ----
    ok = await calculate_monthly_payment.invoke(
        {"principal": 100.0, "annual_rate": 4.9, "years": 30})
    print(f"\n=== 正常调用 ===\n月供: {ok} 元")

    # ---- 4. schema 校验: 缺参/多参抛 ValidationError[189001] ----
    print("\n=== 参数校验（真实行为: 抛 ValidationError） ===")
    for name, bad_args in [
        ("缺参数", {"principal": 100.0}),
        ("多参数", {"principal": 1.0, "annual_rate": 4.9, "years": 30, "hacker": True}),
    ]:
        try:
            await calculate_monthly_payment.invoke(bad_args)
        except Exception as e:
            print(f"{name}: {type(e).__name__}: {str(e)[:80]}...")

    # ---- 5. ServiceAPI: 本地授信系统 HTTP API → RestfulApi 工具 ----
    class CreditAPI(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            q = parse_qs(urlparse(self.path).query).get("q", [""])[0]
            key = self.headers.get("X-API-Key", "")
            body = (json.dumps({"customer_id": q, "risk_tag": "正常", "debt_ratio": 0.42},
                               ensure_ascii=False).encode()
                    if key == "sk-bank-test-001"
                    else b'{"error": "unauthorized"}')
            self.send_response(200 if key == "sk-bank-test-001" else 401)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer(("127.0.0.1", 0), CreditAPI)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    # 凭证放 headers（请求头）—— 不进 input_params, 模型看不到密钥
    credit_card = RestfulApiCard(
        id="credit_system", name="query_risk_tag",
        description="查询客户风险标签与负债率(授信系统 API)",
        input_params={"type": "object",
                      "properties": {"q": {"description": "客户号", "type": "string"}},
                      "required": ["q"]},
        url=f"http://127.0.0.1:{server.server_address[1]}/credit",
        headers={"X-API-Key": "sk-bank-test-001"},
        method="GET",
    )
    credit_tool = RestfulApi(card=credit_card)
    api_res = await credit_tool.invoke({"q": "C1001"})
    print("\n=== ServiceAPI 工具（headers 携带凭证） ===")
    print(f"{type(credit_tool).__name__} -> {api_res}")

    server.shutdown()

    # ---- 6. 验收断言 ----
    assert card.input_params["properties"]["principal"]["type"] == "number"
    assert card.input_params["required"] == ["principal", "annual_rate", "years"]
    assert abs(ok - 5307.27) < 1.0, "100万/4.9%/30年 月供应约 5307 元"
    assert "unauthorized" not in str(api_res) and "risk_tag" in str(api_res)
    assert "X-API-Key" not in json.dumps(credit_card.input_params), "凭证不得进 schema"
    print("\nSUCCESS: Schema 提取/invoke 校验/ServiceAPI+凭证头 全部验收通过")


if __name__ == "__main__":
    asyncio.run(main())
