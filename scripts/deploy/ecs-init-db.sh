#!/usr/bin/env bash
# 仅用于 ECS 首次空库初始化：schema + migrations；演示 seed 必须显式启用。
# 用法: bash scripts/deploy/ecs-init-db.sh [--with-seed]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

COMPOSE=(docker compose -f docker-compose.prod.yml)
MYSQL_SERVICE=mysql
WITH_SEED=false

case "${1:-}" in
  "") ;;
  --with-seed) WITH_SEED=true ;;
  *)
    echo "用法: bash scripts/deploy/ecs-init-db.sh [--with-seed]" >&2
    exit 2
    ;;
esac

if [[ ! -f .env ]]; then
  echo "缺少 .env；请先安装生产配置包" >&2
  exit 1
fi

mysql_import() {
  "${COMPOSE[@]}" exec -T "$MYSQL_SERVICE" sh -ec \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 "$MYSQL_DATABASE"'
}

echo ">> 等待 MySQL 就绪（最多 120s）"
deadline=$((SECONDS + 120))
until "${COMPOSE[@]}" exec -T "$MYSQL_SERVICE" sh -ec \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqladmin ping -h 127.0.0.1 -uroot --silent' \
  >/dev/null 2>&1; do
  if (( SECONDS >= deadline )); then
    echo "MySQL 超时未就绪" >&2
    exit 1
  fi
  sleep 3
done

existing_table_count="$(
  "${COMPOSE[@]}" exec -T "$MYSQL_SERVICE" sh -ec \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"' \
    | tr -d '[:space:]'
)"
if [[ ! "$existing_table_count" =~ ^[0-9]+$ ]]; then
  echo "无法确认目标数据库是否为空，拒绝初始化" >&2
  exit 1
fi
if (( existing_table_count > 0 )); then
  echo "目标数据库已有 ${existing_table_count} 张表；首次初始化脚本拒绝重放 schema/migrations" >&2
  echo "已有数据库请按 docs/development/database.md 审核并执行增量迁移" >&2
  exit 1
fi

echo ">> 导入 schema.sql"
mysql_import < apps/server/db/schema.sql

mapfile -t migration_files < <(printf '%s\n' apps/server/db/migration/V*.sql | sort -V)
for file in "${migration_files[@]}"; do
  echo ">> migration: $(basename "$file")"
  mysql_import < "$file"
done

if [[ "$WITH_SEED" == "true" ]]; then
  echo ">> 显式导入 phase_a_seed.sql"
  mysql_import < apps/server/db/seed/phase_a_seed.sql
else
  echo ">> 跳过 phase_a_seed.sql（生产默认）"
fi

echo ">> 首次数据库初始化完成"
