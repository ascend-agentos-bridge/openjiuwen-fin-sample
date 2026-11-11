# 设计契约 · sample27 模型配置界面 + 保存配置（2026-11-11）

> 追加需求：页面增加「模型设置」界面，可填写并**持久化保存**模型连接信息，重启服务后仍然生效。
> 同时把右侧「团队协作直播」改名为「团队协作实时信息」。
>
> 背景问题：当前模型配置只能靠环境变量（API_KEY/API_BASE/MODEL_NAME），且 8080 上残留旧进程导致
> 前端新协议对不上旧后端（浏览器报「连接提前中断，请稍后重试。」；重新启动又报 `Address already in use`）。
> 本次改动后，用户可以直接在页面里配置模型，不再依赖环境变量。

## 一、模型配置（后端）

### 存储

- 新增 `ModelConfigStore.java`（默认包，和现有 `CreditWebApp` 同级）。
- 存储位置：环境变量 `SAMPLE27_MODEL_CONFIG` 指定的文件；未设置时用**服务启动目录**下的
  `.sample27-model-config.json`（样例即 `java/`，已加入 `.gitignore`；启动目录不可写时回退
  `~/.openjiuwen-fin-sample/sample27-model-config.json`）。默认路径调整原因：部分运行环境（容器/沙箱）
  禁止写用户 home，曾导致「Operation not permitted」保存失败。
- 首次加载时缺失字段回退环境变量：`MODEL_PROVIDER`（默认 `openai`）、`API_BASE`（默认 `https://api.deepseek.com`）、
  `MODEL_NAME`（默认 `deepseek-flash`）、`API_KEY`（默认空）。
- 保存：校验后原子写（临时文件 + `Files.move` REPLACE_EXISTING），尽力设置 POSIX 600 权限；目录不存在时创建。
- 内存缓存 + `synchronized`；保存成功后立即对下一次请求生效（不需要重启）。
- 字段：`provider`、`apiBase`、`modelName`、`apiKey`。

### HTTP 接口（`CreditWebApp`，与 `/chat` 一样只绑 127.0.0.1、校验 Origin 为 8080/localhost、限制 body 大小）

`GET /config` → 200 JSON：

```json
{
  "provider": "openai",
  "apiBase": "https://api.deepseek.com",
  "modelName": "deepseek-flash",
  "hasApiKey": true,
  "apiKeyMasked": "****cdef",
  "source": "file"
}
```

- **绝不返回原始 apiKey**；`apiKeyMasked` 只露末 4 位（key 长度 < 8 时用 `****`）。
- `source`：`file`（本地配置文件里有）、`env`（仅环境变量）、`none`（都没有）。

`POST /config`，body JSON（≤ 8KB）：

```json
{"provider":"openai","apiBase":"https://api.deepseek.com","modelName":"deepseek-flash","apiKey":"sk-...","clearApiKey":false}
```

- `apiKey` 为空字符串/缺省 = **保留已保存的 key**；`clearApiKey:true` = 清空 key。
- 成功：200，返回与 GET 相同结构（保证前端马上拿到掩码/状态）。
- 校验失败：400 `{"error":"<中文原因>"}`。规则：
  - provider 非空、≤32 字符；modelName 非空、≤128 字符；apiBase 以 `http://` 或 `https://` 开头、≤512；
  - apiKey ≤512 字符；所有字段先 trim。
- 其它方法 → 405。

### 模型调用动态生效（`CreditAgents`）

- 删除 `static final MODEL / CLIENT` 的类加载期绑定；每次 `run(...)` 开始时从 `ModelConfigStore` 读取快照
  （provider/apiBase/modelName/apiKey），用它构造 `ModelClientConfig` 与 `ReActAgentConfig`；`createAgent` 增加 settings 参数。
- `apiKey` 为空时**先抛 `IllegalStateException("尚未配置模型 API Key，请点击右上角「模型设置」填写后重试")`**，
  由 `CreditWebApp` 的异常分支转成 `collab error + run_end(error) + error` 事件。
- `CreditWebApp` 异常分支的 reason 调整：`IllegalStateException/IllegalArgumentException` 一律用 `e.getMessage()`
  （不再只对运行者确认分支透传），其它异常仍显示「请检查服务端模型配置及业务前置条件」。
- 业务规则、提示词、工具、事件协议不动；`/chat` 现有行为不变。

### 测试

- 新增 `ModelConfigStoreTest`（继承/普通 JUnit 均可，用 `@TempDir` 或 `Files.createTempDirectory`）：
  1. 默认值 + 环境变量兜底（用可注入的路径构造，不读真实 home）；
  2. save → load roundtrip（provider/apiBase/modelName/key 一致）；
  3. view 不含原始 key，masked 只露末 4 位；
  4. 空 apiKey 保存 = 保留旧 key；`clearApiKey` = 清空；
  5. 非法 apiBase / 空 modelName → 抛 `IllegalArgumentException`；
  6. 文件权限尽力 600（可跳过断言或在非 POSIX 环境跳过）。
- 现有 `mvn test`（11 个用例）必须继续全绿。

## 二、模型设置界面（前端，`web/src/main.js` / `style.css`）

1. Header 在「＋ 新建会话」左侧新增按钮：`<button id="model-config">⚙ 模型设置</button>`；
   - 页面加载时 `GET /config` 初始化；`hasApiKey=false` 时按钮加 `.is-unconfigured`（琥珀色描边/提示「尚未配置模型」），
     `hasApiKey=true` 时普通样式，`title` 显示当前模型名 + 掩码。
2. 点击打开模态框（遮罩 + 居中卡片，`role="dialog"`，Esc/点遮罩/取消关闭）：
   - 字段：`模型服务地址`（#model-api-base）、`模型名称`（#model-model-name）、`Provider`（#model-provider，默认 openai）、
     `API Key`（#model-api-key，password，placeholder：已配置时「已保存 ****cdef，留空表示不修改」，未配置时「sk-...」）；
   - 「清除已保存的 Key」复选框（#model-clear-key）；
   - 底部：状态行（#model-status：当前模型/来源/是否已配置）+ 错误行（#model-error）+ 取消/保存（#model-save）。
   - 保存：`POST /config`，成功 → 关闭模态框、顶部 toast「模型配置已保存，对下一次问答生效」、刷新 header 状态；
     失败 → 模态框内显示 `data.error`，不关闭；保存中按钮 loading（禁用）。
   - **保存后清空 API Key 输入框**；任何地方都不显示原始 key。
3. 首屏无 key 时，在欢迎语下方加一行轻提示（可点击打开设置）：`尚未配置模型服务，请先完成模型设置。`
4. 不写 localStorage/sessionStorage；配置完全由后端保存。
5. 相关 CSS 走现有设计语言（白底、招商红 #c7000b、系统字体、圆角卡片），1440×900 / 1280×800 无溢出；
   模态框层级高于 A2UI，可滚动、移动端不破版。

## 三、改名（全仓库可见文案）

- 「团队协作直播」→「团队协作实时信息」（header、aria-label、空态/等待提示、按钮 title、CSS 注释、README、设计契约）。
- 「直播」相关的动作文案改为「实时展示」等中性表达，例如「团队协作实时信息已开始，等待首个事件…」。
- English eyebrow 可保持不变（TEAM COLLABORATION），不改类名/CSS 变量/事件协议。

## 四、验证与交付

- 后端：`mvn test` 全绿（11 + 新增 ModelConfigStoreTest）；`mvn -DskipTests package` 通过。
- 前端：`npm test`、`npm run build` 通过；构建产物更新到 `java/src/main/resources/ui/`。
- 端到端（headless Chrome，serve 构建产物 + 脚本化事件流 stub 只做 UI 验证）：
  1. 未配置 key 时 header 按钮为未配置态，首屏提示可见；
  2. 打开设置 → 保存（无 key）→ 成功后 header 变已配置态（stub/后端配合返回 hasApiKey=true）→ 刷新页面仍保持；
  3. 保存后 GET /config 返回无原始 key（后端单测覆盖）；
  4. 恢复原事件流验证：改名后的「团队协作实时信息」标题、客户对话富 UI、协作实时信息、审批流转、错误态、1280 布局；
  5. 无 console error、无横向溢出。
- 重启真实服务：杀掉 8080 上的旧进程后启动新服务，`GET /config` 与 `POST /config` 用 curl 验证真实后端；
  `mvn -Preal-model test` 需要 API_KEY，若环境仍无 key 则如实记录未执行。
- 更新 README：模型设置章节（界面入口、保存位置、字段说明、安全边界、重启生效说明）、改名后的界面描述、验证范围。
- 截图归档并更新 README 引用（旧截图里标题是「团队协作直播」，需重新生成）。

---

## 实现状态（2026-11-11）

已落地并验证：

- 后端：`ModelConfigStore`（原子写/600 权限/环境变量兜底/脱敏视图/可注入路径）、`CreditWebApp` 的 `GET/POST /config`、
  `CreditAgents` 每次 run 动态读取快照；`mvn clean package` 通过，17 个用例全绿（新增 `ModelConfigStoreTest` 6 个）。
- 真实后端接口验证（8080 真实进程）：默认值/400 校验/保存掩码/空 Key 保留/clearApiKey 清空/文件 600/重启持久化；
  未配置 Key 时 `/chat` 返回明确错误事件。
- 前端：header ⚙ 模型设置 + 模态框 + 首屏提示；真实浏览器 E2E 走完「未配置 → 弹窗 → 非法地址报错 → 保存 → 刷新保持 → 清除 Key → 提问错误卡」；
  `npm test` 2 项、`npm run build` 通过。
- 改名：`团队协作直播` → `团队协作实时信息`（main.js/style.css/collab.js/collab.css/README/契约），构建产物已更新。

与契约的小偏差（均为实现需要，已复核）：

1. 配置文件里**显式 `"apiKey":""` 视为已清空、不回退 env `API_KEY`**，保证 clearApiKey 在重启后仍生效；其余字段缺失/空白才回退 env。
2. `POST /config` body 上限按契约取 8KB（超限 413）；保存 IO 失败返回 500（契约只规定校验 400，防御性补充）。
3. `handleConfig` 抽成包内静态方法便于验证，路由与行为不变。
4. `vite.config.js` 的 dev proxy 增加 `/config`（否则 `npm run dev` 下配置页会显示未配置态）。
5. 验证限制：本机仍无真实模型 Key，未执行 `mvn -Preal-model test`；用户可在页面填入真实 Key 后直接跑通模型调用。

### 修复记录（2026-11-11 晚）

- 现象：页面保存模型配置报 `模型配置保存失败：模型配置保存失败：/Users/xxx/.openjiuwen-fin-sample: Operation not permitted`。
- 原因：默认配置目录在用户 home，当前运行环境对 workspace 之外的写入有沙箱限制；同时错误信息被 Store 与 WebApp 各加了一次前缀。
- 修复：默认存到服务启动目录下 `.sample27-model-config.json`（已 gitignore，启动目录不可写才回退 home）；
  WebApp 不再重复拼前缀，错误信息只由 Store 生成一次并带配置路径；
  README/.gitignore 同步更新；重启服务后已验证保存、刷新保持、重启保持、清除 Key 均正常。
