FROM python:3.11-slim
ENV PYTHONUNBUFFERED=1 PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
# 固定上游 commit，避免漂移；构建需要网络
ARG AGENT_MEMORY_REF=2a77650
RUN git clone https://atomgit.com/openJiuwen/agent-memory.git /app \
    && git -C /app checkout ${AGENT_MEMORY_REF} \
    && pip install --upgrade pip \
    && pip install -e .
EXPOSE 8137
CMD ["python", "-m", "jiuwen_memory_entry.http_server", "--host", "127.0.0.1", "--port", "8138", "--auth-mode", "dev", "/config/config.yml"]
