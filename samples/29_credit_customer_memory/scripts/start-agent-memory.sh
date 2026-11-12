#!/usr/bin/env bash
# 启动 Agent Memory（本机 Python 3.11）+ 打印 Portal Web 地址。
# 说明：upstream dev auth 强制 loopback，容器化 Portal 在 macOS 会被后端拒绝对外监听；
# 当前样例以本机 Python 服务验证 Portal/REST，docker compose 文件保留为参考。
set -euo pipefail
SAMPLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME="${AGENT_MEMORY_HOME:-$SAMPLE_ROOT/.runtime/agent-memory}"
REF="${AGENT_MEMORY_REF:-2a77650c16f8fe073d8713bc91fc703a7233359d}"
CONFIG="$SAMPLE_ROOT/memory-service/config.yml"

# 页面「模型设置」保存的本机 Agent Memory 配置（gitignored）
if [[ -f "$SAMPLE_ROOT/memory-service/model.local.env" ]]; then
  set -a; source "$SAMPLE_ROOT/memory-service/model.local.env"; set +a
fi

if [[ -z "${LLM_API_KEY:-}" ]]; then
  echo "ERROR: 请先 export LLM_API_KEY=sk-...（DeepSeek 或 OpenAI 兼容）" >&2
  exit 1
fi
export EMBEDDER_BASE_URL="${EMBEDDER_BASE_URL:-http://127.0.0.1:1234/v1}"
export MEMORY_DB_PATH="${MEMORY_DB_PATH:-$RUNTIME/data/memory.db}"

if [[ ! -d "$RUNTIME/.git" ]]; then
  mkdir -p "$(dirname "$RUNTIME")"
  echo "首次启动：clone agent-memory 源码到 $RUNTIME ..."
  git clone https://atomgit.com/openJiuwen/agent-memory.git "$RUNTIME"
  git -C "$RUNTIME" checkout "$REF"
fi
if [[ ! -x "$RUNTIME/.venv/bin/python" ]]; then
  echo "首次启动：创建 Python 3.11 venv 并安装 agent-memory ..."
  ( cd "$RUNTIME" && python3.11 -m venv .venv && .venv/bin/python -m pip install -q --upgrade pip && .venv/bin/python -m pip install -q -e . )
fi
mkdir -p "$(dirname "$MEMORY_DB_PATH")"

# 已运行则直接取 Portal
if curl -fsS --max-time 2 http://127.0.0.1:8137/healthz >/dev/null 2>&1; then
  echo "Agent Memory 已在运行，复用现有实例。"
else
  echo "启动 Agent Memory（本机 127.0.0.1:8137）..."
  ( cd "$RUNTIME" && PYTHONPATH="$RUNTIME" LLM_API_KEY="$LLM_API_KEY" EMBEDDER_BASE_URL="$EMBEDDER_BASE_URL" MEMORY_DB_PATH="$MEMORY_DB_PATH" \
      nohup .venv/bin/python -m jiuwen_memory_entry.http_server --auth-mode dev --host 127.0.0.1 --port 8137 "$CONFIG" > "$RUNTIME/server.log" 2>&1 & echo $! > "$RUNTIME/server.pid" )
  for _ in $(seq 1 60); do
    curl -fsS --max-time 2 http://127.0.0.1:8137/healthz >/dev/null 2>&1 && break
    sleep 2
  done
fi
HEALTH=$(curl -fsS --max-time 3 http://127.0.0.1:8137/healthz)
PORTAL_JSON=$(curl -fsS --max-time 5 -X POST http://127.0.0.1:8137/portal/api/launch \
  -H 'Content-Type: application/json' -H 'Origin: http://127.0.0.1:8137' -d '{}')
PORTAL_URL=$(printf '%s' "$PORTAL_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["portal_url"])')
echo
echo "Agent Memory API : http://127.0.0.1:8137  ($HEALTH)"
echo "Agent Memory Web : $PORTAL_URL"
echo "日志             : $RUNTIME/server.log"
