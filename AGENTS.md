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
   - 根目录 README 必须标注当前样例适配的版本号（老样例 `openjiuwen==0.1.16`；新结构场景样例按各自 README 标注，如 `26_consumer_loan_precheck_ask_user` 的 Python 版为 `openjiuwen==0.1.20`、Java 版为 `com.openjiuwen:agent-core-java:0.1.16`）；
   - 禁止在工程内放置与框架重名的本地目录（会遮蔽 pip 安装的真实包）；
   - 框架升级后，受影响的样例与验收输出必须同步更新并通过回归。

4. **禁止 mock 大模型**。LLM 相关样例必须调用真实模型服务，禁止用 mock/假响应充当运行效果。README 需注明运行前提（可用的 API_BASE/API_KEY/MODEL）。

5. **验收必须基于真实运行**。README 中的"实测输出"只能来自真实运行结果，禁止手写或臆造。

6. **API 以官方 examples 为准**。写样例前先读 `agent-core/examples/` 对应主题的官方样例，import 路径、config 类名、方法签名以其为准；拿不准时读框架源码，不猜。

7. **新样例按「编号 + 场景 + 技术」命名，Python/Java 分目录**。老的编号样例（`samples/01_*`～`samples/25_*`）保持现状、不再改造；之后新增的样例编号从 26 开始，目录名为 `<两位编号>_<场景>_<技术>`（如 `26_consumer_loan_precheck_ask_user`：26 是编号，consumer_loan_precheck 是消费贷预审场景，ask_user 是人工提问技术），场景目录下分 `python/` 与 `java/`，分别放两种语言 Agent Core 的实现：

   ```text
   samples/26_consumer_loan_precheck_ask_user/
   ├── README.md            场景、目录结构、运行与验收（实测输出）
   ├── python/              Python 版实现 + UT（tests/）
   └── java/                Java 版实现 + JUnit（src/test/java/）
   ```

   两种语言各自基于对应 SDK 编写，不互相套壳；某个语言暂未实现时，对应目录的 README 必须如实标注“待实现”，不得伪造运行与验收输出。

8. **业务代码与验证代码分离**。`main.py` / Java `main` 只写生产风格的业务代码：入口、配置、调官方 API、发起与恢复流程、打印业务结果；保持简洁，**禁止**在业务代码里放 `assert`、测试夹具、`SUCCESS` 之类验收逻辑。验收一律写成 UT：
   - Python：`python/tests/test_*.py`，用标准库 `unittest`；需要验证真实模型流程时直接调用真实服务；
   - Java：`java/src/test/java/`，用 JUnit；
   - 全量回归 `run_all_samples.py` 对老样例维持原逻辑（跑 `main.py` / `test.py`），对新结构样例跑 UT。

9. **样例以生产代码为镜，交互由运行者完成**。样例是给业务开发者看“生产里怎么写”的，不是框架内部机制演示：
   - 需要用户提供的输入（客户问题、澄清信息、业务选择）由**运行样例的人自己输入**（终端 `input()` 或等价交互），不得在业务入口里硬编码客户问题、也不得用程序模拟用户回答；
   - README 必须写清每一步的输入输出：运行者要输入什么、Agent 会输出什么、最终得到什么，并附一次**真实交互**的运行记录；
   - 中断/恢复的内部字段（`interrupt_id`、`payload.value`、`ToolCallInterruptRequest` 之类）不要出现在业务代码主流程和 README 主线里；宿主侧确需的少量适配收敛成一个小函数，业务视角只呈现“Agent 追问 → 用户输入 → 继续”；
   - 简化标准是“实际生产代码会怎么写”，不是“框架能力展示得有多全”；能一次普通调用说清的事，不要展开内部回路；
   - 自动化验收仍走 UT（规则单测 + 真实模型集成测试），不靠业务入口里的断言。

## 工程约定

- 场景统一银行金融（信贷、风控、客服等），保持单文件单特性，不过度复杂；
- 老的 sample（编号 01–25）维持原有单语言结构：Python 含 `main.py` + `README.md`，Java 用 `pom.xml` + `src/main/java/`，验收代码放在 `src/test/java/`；
- 新增 sample 按硬约束 7–9：场景目录下 `python/`、`java/` 分语言实现，业务入口只写业务流程、由运行者交互输入，验收写 UT；
- 每个 sample 的 README 包含场景说明、目录结构、交互步骤（运行者每步输入什么、输出什么）、代码讲解、运行方式、验收方式、实测输出、注意点，让开发者直观看到运行效果；
- 每个 sample 的 README 标注其依赖的 openjiuwen Agent Core 版本，且必须基于最新 release 开发；
- 根目录维护 openjiuwen Agent Core 面向开发者的全量特性清单（`FEATURES.md`），每个特性对应一个样例目录，作为样例索引；
- `run_all_samples.py` 一键回归，退出码非 0 即失败；
- 环境依赖写进根 README（Python 版本、openjiuwen 版本、环境变量清单）。
