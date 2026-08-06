import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const root = new URL("../../", import.meta.url);
const MIB = 1024 * 1024;

function read(relativePath) {
  return readFileSync(new URL(relativePath, root), "utf8");
}

function composeConfig(extraArgs = []) {
  const stdout = execFileSync(
    "docker",
    [
      "compose",
      "-f",
      "docker-compose.prod.yml",
      "--env-file",
      ".env.prod.example",
      ...extraArgs,
      "config",
      "--format",
      "json",
    ],
    { cwd: root, encoding: "utf8" },
  );
  return JSON.parse(stdout);
}

test("低成本默认配置不启动 Kafka，显式 profile 才启动", () => {
  const lowCost = composeConfig();
  const withKafka = composeConfig(["--profile", "kafka"]);

  assert.equal(lowCost.services.kafka, undefined);
  assert.ok(withKafka.services.kafka);
  assert.notEqual(withKafka.services.kafka.image, "apache/kafka:latest");
  assert.equal(lowCost.services.server.environment.KAFKA_ENABLED, "false");
  assert.equal(lowCost.services.server.environment.CANAL_ENABLED, "false");
});

test("生产后端接收 LLM Agent 和外部 JWT 配置", () => {
  const config = composeConfig();
  const server = config.services.server;

  assert.equal(server.environment.SPRING_PROFILES_ACTIVE, "llm");
  assert.equal(server.environment.LLM_ENABLED, "true");
  assert.ok(Object.hasOwn(server.environment, "DEEPSEEK_API_KEY"));
  assert.ok(Object.hasOwn(server.environment, "DASHSCOPE_API_KEY"));
  assert.ok(Object.hasOwn(server.environment, "AGENT_MAX_STEPS"));
  assert.ok(Object.hasOwn(server.environment, "AGENT_TURN_TIMEOUT_SECONDS"));
  assert.equal(
    server.environment.AUTH_JWT_PRIVATE_KEY,
    "file:/run/secrets/jwt-private.pem",
  );
  assert.equal(
    server.environment.AUTH_JWT_PUBLIC_KEY,
    "file:/run/secrets/jwt-public.pem",
  );

  const mountedTargets = server.volumes.map((volume) => volume.target);
  assert.ok(mountedTargets.includes("/run/secrets/jwt-private.pem"));
  assert.ok(mountedTargets.includes("/run/secrets/jwt-public.pem"));
});

test("单节点 ES 与敏感目录使用安全默认值", () => {
  const envExample = read(".env.prod.example");
  const gitignore = read(".gitignore");

  assert.match(envExample, /^ES_REPLICAS=0$/m);
  assert.match(envExample, /^KAFKA_ENABLED=false$/m);
  assert.match(envExample, /^HOST_SWAP_SIZE_GB=2$/m);
  assert.match(envExample, /^DEEPSEEK_API_KEY=$/m);
  assert.match(envExample, /^DASHSCOPE_API_KEY=$/m);
  assert.match(gitignore, /^\.local-deploy\/$/m);
  assert.match(gitignore, /^\.production-secrets\/$/m);
});

test("首次部署脚本不执行 env 文件并在构建前校验配置", () => {
  const bootstrap = read("scripts/deploy/ecs-bootstrap.sh");

  assert.doesNotMatch(bootstrap, /^\s*(?:source|\.)\s+\.env\s*$/m);
  assert.match(bootstrap, /validate_env_value/);
  assert.match(bootstrap, /\.production-secrets\/jwt-private\.pem/);
  assert.match(bootstrap, /COMPOSE_PROFILES/);
});

test("数据库初始化仅允许空库且 seed 必须显式启用", () => {
  const initDb = read("scripts/deploy/ecs-init-db.sh");

  assert.doesNotMatch(initDb, /^\s*(?:source|\.)\s+\.env\s*$/m);
  assert.doesNotMatch(initDb, /-p\"?\$ROOT_PASS/);
  assert.match(initDb, /information_schema\.tables/);
  assert.match(initDb, /--with-seed/);
  assert.match(initDb, /if \[\[ \"\$WITH_SEED\" == \"true\" \]\]/);
  assert.match(initDb, /phase_a_seed\.sql/);
});

test("HTTP 入口提供 ACME challenge 且 Compose 预留 HTTPS 挂载", () => {
  const nginx = read("docker/nginx/default.conf");
  const config = composeConfig();
  const proxy = config.services.nginx;

  assert.match(nginx, /\.well-known\/acme-challenge/);
  assert.ok(proxy.ports.some((port) => port.target === 443));
  assert.ok(proxy.volumes.some((volume) => volume.target === "/var/www/certbot"));
  assert.ok(proxy.volumes.some((volume) => volume.target === "/etc/letsencrypt"));
});

test("HTTPS 切换仅在证书和 Nginx 配置验证成功后执行", () => {
  const template = read("docker/nginx/https.conf.template");
  const enableHttps = read("scripts/deploy/ecs-enable-https.sh");

  assert.match(template, /__DOMAIN__/);
  assert.match(template, /ssl_certificate/);
  assert.match(enableHttps, /CERTBOT_IMAGE="certbot\/certbot:v5\.7\.0"/);
  assert.match(enableHttps, /nginx -t/);
  assert.match(enableHttps, /NGINX_CONFIG_PATH/);
});

test("4 GiB 默认配置限制核心服务和进程内存", () => {
  const config = composeConfig();
  const services = config.services;

  assert.equal(services.mysql.deploy.resources.limits.memory, String(640 * MIB));
  assert.equal(services.redis.deploy.resources.limits.memory, String(192 * MIB));
  assert.equal(
    services.elasticsearch.deploy.resources.limits.memory,
    String(1200 * MIB),
  );
  assert.equal(services.server.deploy.resources.limits.memory, String(896 * MIB));
  assert.equal(services.web.deploy.resources.limits.memory, String(384 * MIB));
  assert.equal(services.nginx.deploy.resources.limits.memory, String(64 * MIB));

  assert.ok(services.mysql.command.includes("--max_connections=50"));
  assert.ok(services.mysql.command.includes("--innodb-buffer-pool-size=128M"));
  assert.deepEqual(
    services.redis.command.slice(0, 3),
    ["redis-server", "--maxmemory", "128mb"],
  );
  assert.equal(services.elasticsearch.environment.ES_JAVA_OPTS, "-Xms512m -Xmx512m");
  assert.equal(
    services.server.environment.JAVA_TOOL_OPTIONS,
    "-Xms256m -Xmx512m -XX:MaxMetaspaceSize=192m",
  );
  assert.equal(services.web.environment.NODE_OPTIONS, "--max-old-space-size=256");
});

test("生产服务统一限制 json-file 日志大小和保留数量", () => {
  const config = composeConfig(["--profile", "kafka"]);

  for (const serviceName of Object.keys(config.services)) {
    const logging = config.services[serviceName].logging;
    assert.equal(logging.driver, "json-file", `${serviceName} logging driver`);
    assert.equal(logging.options["max-size"], "10m", `${serviceName} log size`);
    assert.equal(logging.options["max-file"], "3", `${serviceName} log files`);
  }
});

test("首次部署先确保 swap 并串行构建后端和前端", () => {
  const bootstrap = read("scripts/deploy/ecs-bootstrap.sh");

  const swapIndex = bootstrap.indexOf("ecs-ensure-swap.sh");
  const composeUpIndex = bootstrap.indexOf('"${COMPOSE[@]}" up -d');
  const serverBuildIndex = bootstrap.indexOf('"${COMPOSE[@]}" build server');
  const webBuildIndex = bootstrap.indexOf('"${COMPOSE[@]}" build web');

  assert.ok(swapIndex >= 0, "bootstrap should call ecs-ensure-swap.sh");
  assert.ok(swapIndex < composeUpIndex, "swap should be checked before compose up");
  assert.ok(serverBuildIndex >= 0, "server should have its own build command");
  assert.ok(webBuildIndex > serverBuildIndex, "web should build after server");
  assert.doesNotMatch(bootstrap, /build server web/);

  const swap = read("scripts/deploy/ecs-ensure-swap.sh");
  assert.match(swap, /\/proc\/meminfo/);
  assert.match(swap, /mkswap/);
  assert.match(swap, /swapon/);
  assert.match(swap, /\/etc\/fstab/);
});
