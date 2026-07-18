# 数据备份脚本

内容包源码、文章 Markdown 和使用到的媒体文件由 Git 保存；MySQL 与 Redis 中的运行时状态需要另行备份。Elasticsearch 索引可以从权威数据重建，不作为权威备份。

点赞/收藏成员关系只保存在 Redis bitmap 中，不能从 MySQL 重建。正式导入内容包或执行数据维护前，必须在同一维护窗口分别生成 MySQL 与 Redis 的仓库外备份。

维护前备份用于整批回滚；正式导入完成并通过幂等审计后，应在主后端仍停机时再生成一组 MySQL 与 Redis 配对备份，作为包含新互动事实的恢复基线。恢复完整 Redis RDB 后，不要再对点赞/收藏执行 Kafka earliest 回放；SDS 缺失时由 bitmap 重建即可。

## MySQL 备份

从仓库根目录执行：

```powershell
.\scripts\backup\backup-mysql.ps1 `
  -DestinationRoot "D:\1.hhh\backups\Chtholly-Hub"
```

脚本会从项目 `.env` 加载连接凭据，调用正在运行的 `mysql` 容器完成一致性导出，并在“UTC 毫秒时间戳 + 随机运行标识”的独占目录下生成：

- 压缩 SQL：`<database>-<timestamp>.zip`
- 元数据与校验值：`<database>-<timestamp>.json`

容器导出先复制到宿主机 `.partial` 文件，校验 SQL 非空后生成临时归档，再通过同目录原子改名发布归档与元数据。元数据中的字节数和 SHA-256 以最终归档为准；归档或元数据发布前失败时，脚本会删除本次运行产生的宿主机半成品和空目录。已有的运行目录不会被覆盖或复用。

若 ZIP 与元数据已经完成校验和原子发布，而最后的容器临时 SQL 清理失败，脚本会保留可恢复的宿主机备份并返回失败；错误信息会同时给出保留目录与容器临时文件路径，运维人员只需处理容器 `/tmp/chtholly-mysql-backup-*.sql` 残留，不应删除已验证的宿主机备份。

MySQL 密码只在备份进程期间写入宿主进程的 `MYSQL_PWD`，`docker exec` 仅接收要继承的环境变量名，参数和元数据均不包含密码值；脚本结束时会恢复调用前的 `MYSQL_PWD` 状态。

目标目录必须满足以下条件：

- 是绝对路径；
- 位于 D 盘；
- 位于共享 Git 仓库以及所有已登记 worktree 之外；
- 路径及其已存在的任一父目录不包含符号链接、junction 或其他 reparse point。

只检查参数而不导出：

```powershell
.\scripts\backup\backup-mysql.ps1 `
  -DestinationRoot "D:\1.hhh\backups\Chtholly-Hub" `
  -Database chtholly `
  -ValidateOnly
```

运行契约测试：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\backup\test-backup-mysql.ps1
```

## Redis 备份

Redis 容器运行时，从仓库根目录执行：

```powershell
.\scripts\backup\backup-redis.ps1 `
  -DestinationRoot "D:\1.hhh\backups\Chtholly-Hub" `
  -Container redis
```

脚本通过容器内的 `redis-cli --rdb` 生成一致性 RDB，先复制到宿主机 `.partial` 文件，确认非空后再原子改名，并在“UTC 毫秒时间戳 + 随机运行标识”的独占目录中生成：

- Redis RDB：`redis-<timestamp>.rdb`
- 元数据与校验值：`redis-<timestamp>.json`

元数据记录 RDB 文件名、字节数和 SHA-256，不记录 Redis 密码。脚本从项目 `.env` 读取可选的 `REDIS_PASSWORD`，只通过 `REDISCLI_AUTH` 环境变量名交给容器进程，不把密码值放进命令参数或输出。

Redis 备份目标遵循与 MySQL 相同的路径保护：必须位于 D 盘的绝对路径，处于共享 Git 仓库及所有已登记 worktree 之外，并且路径及其已存在父目录不能包含符号链接、junction 或其他 reparse point。运行目录已存在时脚本会拒绝复用，也不会删除或覆盖其中的内容。

RDB 或元数据发布前失败时，脚本会清除本次运行创建的宿主机半成品和空目录。若 RDB 与元数据已经完成校验和原子发布，而最后的容器临时文件清理失败，脚本会保留可恢复的宿主机备份并返回失败；运维人员应根据报错检查容器 `/tmp/chtholly-redis-backup-*.rdb` 残留，而不应删除已验证的备份。

只检查路径和容器名而不连接 Redis：

```powershell
.\scripts\backup\backup-redis.ps1 `
  -DestinationRoot "D:\1.hhh\backups\Chtholly-Hub" `
  -Container redis `
  -ValidateOnly
```

运行契约测试：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\backup\test-backup-redis.ps1
```

不要把备份放进仓库，也不要把数据库密码写进命令或备份元数据。恢复 MySQL 或 Redis 都是显式运维动作；备份脚本不会自动覆盖当前运行状态。
