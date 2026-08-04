#!/usr/bin/env bash
# ECS 单机首次部署：配置预检、构建/启动 Compose、初始化空数据库、健康检查。
# 用法: bash scripts/deploy/ecs-bootstrap.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

COMPOSE=(docker compose -f docker-compose.prod.yml)

if [[ ! -f .env ]]; then
  cp .env.prod.example .env
  echo "已从 .env.prod.example 创建 .env；请填写生产配置后重新运行" >&2
  exit 1
fi

env_value() {
  local key="$1"
  local line
  line="$(grep -E "^${key}=" .env | tail -n 1 || true)"
  printf '%s' "${line#*=}" | tr -d '\r'
}

validate_env_value() {
  local key="$1"
  local value
  value="$(env_value "$key")"
  if [[ -z "$value" || "$value" == change_me* || "$value" == *yourdomain.com* ]]; then
    echo ".env 中的 ${key} 缺失或仍为占位值" >&2
    exit 1
  fi
}

validate_env_value MYSQL_ROOT_PASSWORD
validate_env_value MYSQL_PASSWORD
validate_env_value OWNER_BOOTSTRAP_PASSWORD
validate_env_value NEXT_PUBLIC_SITE_URL

for key_file in \
  .production-secrets/jwt-private.pem \
  .production-secrets/jwt-public.pem; do
  if [[ ! -s "$key_file" ]]; then
    echo "缺少 JWT 密钥文件: $key_file" >&2
    exit 1
  fi
done

llm_enabled="$(env_value LLM_ENABLED)"
spring_profiles="$(env_value SPRING_PROFILES_ACTIVE)"
if [[ "$llm_enabled" == "true" ]]; then
  validate_env_value DEEPSEEK_API_KEY
  validate_env_value DASHSCOPE_API_KEY
  if [[ ",${spring_profiles}," != *,llm,* ]]; then
    echo "LLM_ENABLED=true 时 SPRING_PROFILES_ACTIVE 必须包含 llm" >&2
    exit 1
  fi
fi

kafka_enabled="$(env_value KAFKA_ENABLED)"
compose_profiles="$(env_value COMPOSE_PROFILES | tr -d ' ')"
if [[ "$kafka_enabled" == "true" && ",${compose_profiles}," != *,kafka,* ]]; then
  echo "KAFKA_ENABLED=true 时 COMPOSE_PROFILES 必须包含 kafka" >&2
  exit 1
fi
if [[ "$kafka_enabled" != "true" && ",${compose_profiles}," == *,kafka,* ]]; then
  echo "COMPOSE_PROFILES 包含 kafka 时 KAFKA_ENABLED 必须为 true" >&2
  exit 1
fi
if [[ "$(env_value CANAL_ENABLED)" == "true" && "$kafka_enabled" != "true" ]]; then
  echo "CANAL_ENABLED=true 时必须同时启用 Kafka" >&2
  exit 1
fi

if [[ "$(env_value STORAGE_TYPE)" == "oss" ]]; then
  validate_env_value OSS_ENDPOINT
  validate_env_value OSS_ACCESS_KEY_ID
  validate_env_value OSS_ACCESS_KEY_SECRET
  validate_env_value OSS_BUCKET
fi

echo ">> 校验 Docker Compose 配置"
"${COMPOSE[@]}" config --quiet

echo ">> 构建 server 与 web 镜像（首次约 5–10 分钟）"
"${COMPOSE[@]}" build server web

echo ">> 启动生产服务"
"${COMPOSE[@]}" up -d

echo ">> 初始化空数据库"
if [[ "$(env_value SEED_PHASE_A)" == "true" ]]; then
  bash scripts/deploy/ecs-init-db.sh --with-seed
else
  bash scripts/deploy/ecs-init-db.sh
fi

echo ">> 重启 server 以触发 ES 索引回灌"
"${COMPOSE[@]}" restart server

http_port="$(env_value HTTP_PORT)"
http_port="${http_port:-80}"
echo ">> 等待 HTTP 入口（最多 180s）"
deadline=$((SECONDS + 180))
until curl -fsS "http://127.0.0.1:${http_port}/" -o /dev/null 2>/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "首页未响应，请检查: ${COMPOSE[*]} ps && ${COMPOSE[*]} logs --tail=50 server web nginx" >&2
    exit 1
  fi
  sleep 5
done

echo ""
echo "=========================================="
echo " 首次部署完成"
echo " 浏览器访问: $(env_value NEXT_PUBLIC_SITE_URL)"
echo " API 探活:   curl http://127.0.0.1:${http_port}/api/v1/posts/feed?page=1&size=1"
echo "=========================================="
curl -fsS "http://127.0.0.1:${http_port}/api/v1/posts/feed?page=1&size=1" | head -c 400 || true
echo ""
