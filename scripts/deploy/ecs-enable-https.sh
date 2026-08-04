#!/usr/bin/env bash
# 使用 Certbot webroot 签发单域名证书，验证 Nginx 配置后再切换 HTTPS。
# 用法: bash scripts/deploy/ecs-enable-https.sh [domain] [email]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

COMPOSE=(docker compose -f docker-compose.prod.yml)
CERTBOT_IMAGE="certbot/certbot:v5.7.0"

if [[ ! -f .env ]]; then
  echo "缺少 .env；请先安装生产配置包并完成 HTTP 部署" >&2
  exit 1
fi

env_value() {
  local key="$1"
  local line
  line="$(grep -E "^${key}=" .env | tail -n 1 || true)"
  printf '%s' "${line#*=}" | tr -d '\r'
}

set_env_value() {
  local key="$1"
  local value="$2"
  local temp
  temp="$(mktemp .env.XXXXXX)"
  awk -v key="$key" -v value="$value" '
    BEGIN { replaced = 0 }
    index($0, key "=") == 1 { print key "=" value; replaced = 1; next }
    { print }
    END { if (!replaced) print key "=" value }
  ' .env > "$temp"
  chmod 600 "$temp"
  mv "$temp" .env
}

domain="${1:-$(env_value TLS_DOMAIN)}"
email="${2:-$(env_value TLS_EMAIL)}"
if [[ ! "$domain" =~ ^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$ ]]; then
  echo "域名格式无效: $domain" >&2
  exit 2
fi
if [[ ! "$email" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
  echo "证书通知邮箱格式无效" >&2
  exit 2
fi

install -d -m 755 .production-secrets/acme-webroot
install -d -m 700 .production-secrets/letsencrypt .production-secrets/nginx

echo ">> 确认 HTTP Nginx 正在运行"
"${COMPOSE[@]}" up -d nginx

echo ">> 为 ${domain} 签发或复用证书"
docker run --rm \
  -v "$ROOT/.production-secrets/letsencrypt:/etc/letsencrypt" \
  -v "$ROOT/.production-secrets/acme-webroot:/var/www/certbot" \
  "$CERTBOT_IMAGE" certonly \
  --webroot --webroot-path /var/www/certbot \
  --domain "$domain" \
  --email "$email" \
  --agree-tos --no-eff-email --non-interactive --keep-until-expiring

cert_dir=".production-secrets/letsencrypt/live/${domain}"
if [[ ! -s "$cert_dir/fullchain.pem" || ! -s "$cert_dir/privkey.pem" ]]; then
  echo "Certbot 返回后未找到完整证书，保持 HTTP 配置" >&2
  exit 1
fi

runtime_config=".production-secrets/nginx/https.conf"
temp_config="${runtime_config}.tmp"
sed "s/__DOMAIN__/${domain}/g" docker/nginx/https.conf.template > "$temp_config"
chmod 644 "$temp_config"
mv "$temp_config" "$runtime_config"

echo ">> 在临时容器中执行 nginx -t"
NGINX_CONFIG_PATH="./${runtime_config}" "${COMPOSE[@]}" run --rm --no-deps nginx nginx -t

set_env_value NGINX_CONFIG_PATH "./${runtime_config}"
set_env_value TLS_DOMAIN "$domain"
set_env_value TLS_EMAIL "$email"

echo ">> 切换并重载 HTTPS Nginx"
"${COMPOSE[@]}" up -d --no-deps --force-recreate nginx
curl -fsS "https://${domain}/health" -o /dev/null
echo ">> HTTPS 已启用: https://${domain}/"
