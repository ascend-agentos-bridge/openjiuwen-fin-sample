#!/usr/bin/env bash
# Build once per invocation; forward each argument without shell evaluation.
set -uo pipefail
benchmark_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)" || exit 2
if (( BASH_VERSINFO[0] < 4 )); then
  printf '%s\n' '需要 Bash 4 或更新版本。' >&2
  exit 2
fi
if ! command -v java >/dev/null 2>&1; then
  printf '%s\n' '未找到 Java；请安装 JDK 17+ 并配置 JAVA_HOME/PATH。报告未生成。' >&2
  exit 2
fi
maven_cmd="${MAVEN_CMD:-mvn}"
if ! command -v "$maven_cmd" >/dev/null 2>&1; then
  printf '%s\n' '未找到 Maven；请安装 Maven 3.9+ 或设置 MAVEN_CMD。报告未生成。' >&2
  exit 2
fi
java_version="$(java -version 2>&1)" || { printf '%s\n' '无法读取 Java 版本；报告未生成。' >&2; exit 2; }
java_pattern='version "([0-9]+)'
if [[ ! "$java_version" =~ $java_pattern ]] || (( BASH_REMATCH[1] < 17 )); then
  printf '%s\n' '需要 JDK 17 或更新版本；报告未生成。' >&2
  exit 2
fi
maven_version="$("$maven_cmd" -version 2>&1)" || { printf '%s\n' '无法读取 Maven 版本；报告未生成。' >&2; exit 2; }
maven_pattern='Apache Maven ([0-9]+)\.([0-9]+)'
if [[ ! "$maven_version" =~ $maven_pattern ]] || (( BASH_REMATCH[1] < 3 || (BASH_REMATCH[1] == 3 && BASH_REMATCH[2] < 9) )); then
  printf '%s\n' '需要 Maven 3.9 或更新版本；报告未生成。' >&2
  exit 2
fi
build_log="$(mktemp "${TMPDIR:-/tmp}/agent-hint-build.XXXXXX")" || exit 2
printf '构建日志: %s\n' "$build_log"
if ! "$maven_cmd" -q -B -f "$benchmark_dir/pom.xml" -DskipTests package >"$build_log" 2>&1; then
  printf '构建失败；报告未生成。请查看: %s\n' "$build_log" >&2
  exit 2
fi
benchmark_jar="$benchmark_dir/target/agent-hint-benchmark.jar"
if [[ ! -f "$benchmark_jar" ]]; then
  printf '构建产物不存在: %s；报告未生成。\n' "$benchmark_jar" >&2
  exit 2
fi
# exec preserves Java's status and SIGINT/SIGTERM semantics, without a tee pipeline.
exec java -Dfile.encoding=UTF-8 -Dagent.hint.benchmark.dir="$benchmark_dir" -jar "$benchmark_jar" "$@"
