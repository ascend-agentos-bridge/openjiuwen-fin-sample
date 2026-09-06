# AGENTS.md — openjiuwen-sample 工程开发约束

本仓库是 **openjiuwen Agent Core 的样例工程**（samples），不是框架本体。任何 AI 代理与本仓库的开发者必须遵守以下约束。

## 硬约束

1. **禁止自封装接口**。样例代码只能 import 开源 `openjiuwen` 包（agent-core 仓库）暴露的 API。禁止在本仓库内实现 `llm.py / tool.py / agent.py / workflow.py` 之类的"配套核心库"来替代或包装框架能力。
   - 历史教训：本仓库曾因内网装包失败而自建了一套 API，导致样例无法对齐真实开源项目——已被要求全部迁移，该目录已删除/归档（见 `reference/`，仅供追溯）。

2. **认为需要封装时 = 提 PR 给社区**。如果发现开源 API 对开发者不够易用、确实需要一层封装，这属于框架改进，正确路径是：
   - 在 `agent-core` 仓库提 Issue 讨论；
   - 提交 PR 把易用性改进做进框架本体；
   - 样例仓库同步更新到包含该改进的新版本。
   - 禁止把封装留在样例仓库私有使用。

3. **只用最新 release 版本**。样例以开源 openjiuwen Agent Core 的最新 release 为准：
   - 运行样例前先确认版本：`python -c "import openjiuwen; print(openjiuwen.__version__)"`；
   - 根目录 README 必须标注当前样例适配的版本号（当前为 `openjiuwen==0.1.16`）；
   - 禁止在工程内放置与框架重名的本地目录（会遮蔽 pip 安装的真实包）；
   - 框架升级后，受影响的样例与验收输出必须同步更新并通过回归。

4. **禁止 mock 大模型**。LLM 相关样例必须调用真实模型服务，禁止用 mock/假响应充当运行效果。README 需注明运行前提（可用的 API_BASE/API_KEY/MODEL）。

5. **验收必须基于真实运行**。README 中的"实测输出"只能来自真实运行结果，禁止手写或臆造。

6. **API 以官方 examples 为准**。写样例前先读 `agent-core/examples/` 对应主题的官方样例，import 路径、config 类名、方法签名以其为准；拿不准时读框架源码，不猜。

## 工程约定

- 场景统一银行金融（信贷、风控、客服等），保持单文件单特性，不过度复杂；
- 每个 sample 独立文件夹，含 `main.py` + `README.md`（场景/代码详解/运行方式/验收方式/实测输出/注意点），README 要让开发者直观看到运行效果；
- 每个 sample 的 README 标注其依赖的 openjiuwen Agent Core 版本，且必须基于最新 release 开发；
- 根目录维护 openjiuwen Agent Core 面向开发者的全量特性清单（`FEATURES.md`），每个特性对应一个样例目录，作为样例索引；
- `run_all_samples.py` 一键回归，退出码非 0 即失败；
- 环境依赖写进根 README（Python 版本、openjiuwen 版本、环境变量清单）。
