# 数据备份脚本

内容包源码、文章 Markdown 和使用到的媒体文件由 Git 保存；MySQL 中的运行时状态需要另行备份。Redis 计数和 Elasticsearch 索引可以从 MySQL 重建，不作为权威备份。

## MySQL 备份

从仓库根目录执行：

```powershell
.\scripts\backup\backup-mysql.ps1 `
  -DestinationRoot "D:\1.hhh\backups\Chtholly-Hub"
```

脚本会从项目 `.env` 加载连接凭据，调用正在运行的 `mysql` 容器完成一致性导出，并在时间戳目录下生成：

- 压缩 SQL：`<database>-<timestamp>.zip`
- 元数据与校验值：`<database>-<timestamp>.json`

目标目录必须满足以下条件：

- 是绝对路径；
- 位于 D 盘；
- 位于仓库目录之外。

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

不要把备份放进仓库，也不要把数据库密码写进命令或备份元数据。
