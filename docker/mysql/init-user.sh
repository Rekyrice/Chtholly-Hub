#!/bin/bash
# 首次初始化时收紧应用账号权限（仅 DML；DDL 由 root + 部署脚本执行）
set -euo pipefail

APP_USER="${MYSQL_USER:-chtholly}"
APP_PASS="${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}"
APP_DB="${MYSQL_DATABASE:-chtholly}"

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<EOSQL
CREATE USER IF NOT EXISTS '${APP_USER}'@'%' IDENTIFIED BY '${APP_PASS}';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '${APP_USER}'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`${APP_DB}\`.* TO '${APP_USER}'@'%';
FLUSH PRIVILEGES;
EOSQL
