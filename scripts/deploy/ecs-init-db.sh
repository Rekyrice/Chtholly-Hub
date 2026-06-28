#!/usr/bin/env bash
# 在 ECS 上、compose 已启动且 mysql 健康后执行：建表 + migration + 种子数据
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

COMPOSE=(docker compose -f docker-compose.prod.yml)
MYSQL_SERVICE=mysql

if [[ ! -f .env ]]; then
  echo "缺少 .env，请先 cp .env.prod.example .env 并填写 MYSQL_PASSWORD" >&2
  exit 1
fi

# shellcheck disable=SC1091
source .env

ROOT_PASS="${MYSQL_ROOT_PASSWORD:-${MYSQL_PASSWORD:-}}"
if [[ -z "$ROOT_PASS" ]]; then
  echo "请在 .env 中设置 MYSQL_ROOT_PASSWORD 或 MYSQL_PASSWORD" >&2
  exit 1
fi

DB="${MYSQL_DATABASE:-chtholly}"

echo ">> 等待 MySQL 就绪（最多 120s）"
deadline=$((SECONDS + 120))
until "${COMPOSE[@]}" exec -T "$MYSQL_SERVICE" mysqladmin ping -h 127.0.0.1 -uroot -p"$ROOT_PASS" --silent 2>/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "MySQL 超时未就绪" >&2
    exit 1
  fi
  sleep 3
done

echo ">> 导入 schema.sql"
"${COMPOSE[@]}" exec -T "$MYSQL_SERVICE" mysql -uroot -p"$ROOT_PASS" --default-character-set=utf8mb4 "$DB" \
  < apps/server/db/schema.sql

for f in $(ls apps/server/db/migration/V*.sql | sort -t'_' -k1 -V); do
  echo ">> migration: $(basename "$f")"
  "${COMPOSE[@]}" exec -T "$MYSQL_SERVICE" mysql -uroot -p"$ROOT_PASS" --default-character-set=utf8mb4 "$DB" < "$f"
done

echo ">> 导入 phase_a_seed.sql"
"${COMPOSE[@]}" exec -T "$MYSQL_SERVICE" mysql -uroot -p"$ROOT_PASS" --default-character-set=utf8mb4 "$DB" \
  < apps/server/db/seed/phase_a_seed.sql

echo ">> 完成。请确认 OSS 种子正文已上传，然后: docker compose -f docker-compose.prod.yml restart server"
