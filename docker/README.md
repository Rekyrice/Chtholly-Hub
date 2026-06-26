# 本地基础设施说明

Chtholly Hub **不在本仓库内启动** MySQL / Redis / Kafka / Elasticsearch。  
本地依赖由外部目录统一管理：

**`D:\1.hhh\Application\Docker`**

## 运行中的服务

| 服务 | 端口 | 数据目录（D 盘） |
|------|------|------------------|
| MySQL 8 | 3306 | `mysql_data` |
| Redis | 6379 | `redisSave` |
| Kafka | 9092 | `kafka_data` |
| Elasticsearch | 9200 | `elasticsearch_data` |

数据库名：**`chtholly`**。

## 启动 / 检查

```powershell
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

MySQL 连接示例（PowerShell 下密码含 `!` 时用环境变量，避免交互 `-p` 出错）：

```powershell
docker exec -e MYSQL_PWD='你的密码' mysql mysql -uroot -e "USE chtholly; SHOW TABLES;"
```

## Elasticsearch 单独启动（若未运行）

**只映射 9200**，不要映射 9300（Windows 可能占用 9223–9322 端口段）：

```powershell
docker run -d --name elasticsearch --restart always `
  -p 9200:9200 `
  -v "D:/1.hhh/Application/Docker/elasticsearch_data:/usr/share/elasticsearch/data" `
  -e "discovery.type=single-node" `
  -e "xpack.security.enabled=false" `
  -e "ES_JAVA_OPTS=-Xms512m -Xmx512m" `
  docker.elastic.co/elasticsearch/elasticsearch:8.11.0
```

验证：`curl http://localhost:9200`

## 与本项目的关系

1. 复制 Monorepo 根目录 `.env.example` → `.env`，填入 MySQL 密码与 OSS 凭证  
2. 启动 `apps/server`（`:8888`，8080 在 Windows 上常被 WinNAT 锁死）  
3. 启动 `apps/web`（`:3000`，API 通过 rewrites 代理到后端）

详见仓库根目录 [README.md](../README.md)。
