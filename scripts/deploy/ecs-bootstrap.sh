#!/usr/bin/env bash
# ECS 单机首次部署：构建/启动 compose + 初始化 DB + 健康检查
# 用法: bash scripts/deploy/ecs-bootstrap.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

COMPOSE=(docker compose -f docker-compose.prod.yml)

if [[ ! -f .env ]]; then
  echo ">> 从 .env.prod.example 生成 .env（请编辑 MYSQL_PASSWORD、OSS 等后再跑）"
  cp .env.prod.example .env
  echo "已创建 .env，请编辑后重新运行本脚本" >&2
  exit 1
fi

# shellcheck disable=SC1091
source .env
if [[ -z "${MYSQL_PASSWORD:-}" || "${MYSQL_PASSWORD}" == "change_me_strong_password" ]]; then
  echo "请先在 .env 中设置强密码 MYSQL_PASSWORD" >&2
  exit 1
fi

echo ">> docker compose build（首次约 5–10 分钟）"
"${COMPOSE[@]}" build server web

echo ">> docker compose up -d"
"${COMPOSE[@]}" up -d

echo ">> 初始化数据库"
bash scripts/deploy/ecs-init-db.sh

echo ">> 重启 server 以触发 ES 索引回灌"
"${COMPOSE[@]}" restart server

echo ">> 等待 HTTP 入口（最多 180s）"
deadline=$((SECONDS + 180))
until curl -fsS "http://127.0.0.1:${HTTP_PORT:-80}/" -o /dev/null 2>/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "首页未响应，请检查: ${COMPOSE[*]} ps && ${COMPOSE[*]} logs --tail=50 server web nginx" >&2
    exit 1
  fi
  sleep 5
done

echo ""
echo "=========================================="
echo " 部署完成"
echo " 浏览器访问: http://<ECS公网IP>/"
echo " API 探活:   curl http://127.0.0.1/api/v1/posts/feed?page=1&size=1"
echo "=========================================="
curl -fsS "http://127.0.0.1:${HTTP_PORT:-80}/api/v1/posts/feed?page=1&size=1" | head -c 400 || true
echo ""
