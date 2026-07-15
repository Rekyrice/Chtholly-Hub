这次实验起于一个很小的改动：把字符切片从 500 调到 800，检索会怎样移动？我把问题、答案片段和语料提交都冻结后跑了三次。500 字符方案的 Hit@1 是 0.375，MRR 是 0.569139；800 字符方案的 Hit@1 仍为 0.375，MRR 降到 0.565179。差值只有 0.003960，逐问里也同时有改善与退步。

这里使用 TF-IDF 余弦，只是一条无模型词面基线。它没有调用 LLM、外部 embedding 或项目里的向量索引。七份文件、八个问题也支撑不了“最佳 chunk”之类的大判断。我想留下的是一组能在别的工作树复跑、能查到每个标签依据的个人实验记录。

## 把语料和标签钉在提交上

正式证据放在 `content/seed/content-v3/evidence/rag-chunk-size/`：`experiment.py` 是运行脚本，`queries.json` 保存查询与答案片段，`results.json` 是 canonical 结果，`visuals.html` 从结果动态生成三张图。复跑一份结果的命令是：

```powershell
python content/seed/content-v3/evidence/rag-chunk-size/experiment.py --output .codex-tmp/seed-content-v3/technical/rag_chunk_results-a.json
```

脚本先用 `git rev-parse --show-toplevel` 找仓库，再逐个执行 `git show c9f0747298c1267e4d1e3767fdfc5fb12185cb7e:path`。它不会读当前工作树里的语料。固定输入包括根目录 `README.md`、`AGENTS.md`、后端 README，以及 knowledge 目录里的四份角色文档；结果同时记录七个 blob 的 SHA-256。缺少 commit、路径或 UTF-8 blob 时，脚本直接以非零状态退出。

这道约束专门处理“我的工作树刚好没变”带来的假复现。测试在项目内临时建了一个小 Git 仓库，提交一份文档后再改工作树内容，随后对同一提交读取两次。两次 blob 保持一致，说明输入来自对象库。正式运行也会把每个文件的路径和内容哈希写进结果；另一台机器拿到相同提交时，可以先比较七个哈希，再谈指标是否一致。

八问的 relevant 定义也比“同文件同标题”更窄。一个 chunk 需要同时匹配 source path、Markdown heading，并在自身文本里包含至少一条冻结的 evidence substring。比如缓存问题的 evidence 是架构决策表里写明 Caffeine L1、Redis L2 和 DB 的一行。若同一 heading 被切成多个 chunk，只有实际带着这行答案的 chunk 才算 relevant；答案文字分落两个 chunk 也不会拼起来算命中。

我把两个原本过宽的问题改成“哪个组件负责 Bangumi 数据同步”和“OSS 上传采用什么方式”。前者的 evidence 是后端模块表里的 bangumi 行与 `BangumiSyncJob`，后者是根 README 的“OSS 预签名直传”。substring 标签依旧有限：它能防止同标题空命中，却无法判断同义改写，也不替代人工阅读。

其余标签也尽量落到能回答问题的短句。站长配置绑定默认值为一的环境变量行；通知绑定 Spring ApplicationEvent 与非关键路径说明；Agent 记忆绑定内存存储及迁移计划；搜索绑定 IK 分词；Neo4j 绑定 MySQL 邻接表、数据规模和 Java BFS。脚本在切片前检查这些文字是否仍位于登记标题下，切片后再检查两种窗口里是否各有一个 chunk 完整包含它们。任何一层不满足都会终止运行。

## 从 parser 到连续汉字二元组

脚本按 Markdown heading 分段，忽略 fenced code 中以 `#` 开头的命令注释，再在每段内做固定字符窗口。窗口单位是 Python 字符串的 Unicode code point，chunk size 分别为 500、800，overlap 固定 80，窗口不跨 heading。两组由此得到 62 和 51 个 chunk。

tokenizer 保留中文单字，只在连续汉字 run 内生成相邻二元组，英文和数字按连续 token 转小写。这个边界是实际调试出来的：早期版本先抽完全部汉字再配对，`中 abc 文`、`中，文`、`中\n文`都会伪造出“中文”。回归测试锁住了三种断点，同时确认连续的“中文”仍产生二元组。

另一个早期问题出在标题解析。开发文档的代码块里有以井号开头的命令注释，普通正则会把它们误当成新标题，后续段落便挂到一个并不存在的 heading 下。修正后 parser 跟踪代码围栏，只在围栏外识别标题。这个错误曾让八问 relevant 全部消失，比指标变差更容易误导人：算法没检索错，是切片前就把标签坐标拆坏了。

chunk 向量使用 TF-IDF，查询与 chunk 做余弦相似度。排序按 score 降序，同分时再按 source path、chunk index 升序，保留 top 3。Hit@1 检查首位是否 relevant；MRR 取第一个 answer-bearing chunk 排名的倒数再求平均。脚本会先验证每问在两种窗口下都存在 answer-bearing chunk，找不到就终止，避免把部分回答悄悄算进指标。

## Hit@1 持平，逐问排名并不安静

![八个固定问题在两种切片下的首位结果](asset:rag-recall-table "answer-bearing 逐问结果")

*表格由 tracked results.json 生成；relevant 同时核对路径、标题与 chunk 内 evidence。*

两组都只有站长配置、Agent 记忆、搜索分词三问排在第一，通知和 Bangumi 组件都排第二。缓存从第三到第四，OSS 上传方式从第七升到第五，Neo4j 从第十三降到第十四。Hit@1 把这些移动都保留在“未命中”一侧，MRR 才会反映距离变化。800 的 MRR 略低，主要方向却不统一，单看汇总数很容易把 OSS 的改善漏掉。

逐问表还有一个容易忽略的细节：top 1 与 relevant rank 分开显示。通知的首位来自后端模块表，冻结答案却在架构决策；Bangumi 的首位也落到其他相关段落，answer-bearing 模块行位于第二。它们都说明“文本看起来相关”和“包含这次冻结答案”是两套判定。词面基线可以把相关词推到前面，却未必把回答问题的那一段放在第一。

三次运行分别写入 ignored 的 a、b、c 文件，三份 JSON 字节完全相同，SHA-256 都是 `cac91a73bbf5f37fd2a8a259834e7100f4ec02bf5c6485a3fd7efa52a2bd4f62`。正式 `results.json` 与它们同哈希。结果不记录时间，JSON 固定 UTF-8、键顺序和换行；语料 blob、查询标签、切片参数与 tie-break 都跟着结果留下。

## 缓存问题为什么掉到第四

![文章缓存查询的 top 3 与 answer-bearing 行](asset:rag-failed-query "缓存查询排名变化")

*当 relevant 掉出 top 3，图中额外列出真实 relevant 行和 score。*

我看到 800 字符结果时，先注意到缓存查询的前三名里有两个来自后端模块表：同一 heading 的 chunk 4 和 chunk 5 分别占住第一、第三，“后端（Java）”位于第二。它们都提到了缓存相关词，却没有冻结的 L1、L2、DB 答案行。架构决策里的 answer-bearing chunk 排在第四，所以图里必须把第四行画出来，不能只展示 top 3 再用文字宣称排名。

500 字符时，架构决策行还能留在第三。扩大窗口改变了词频、向量长度和 IDF 文档数，几个相近段落随之换位。这个观察只描述当前词面规则；它没有说明较长切片必然稀释关键词。OSS 的排名向前、Neo4j 的排名向后，也说明同一参数对不同问法可能给出相反方向。

## 这份小记录能回答到哪里

我会把 Hit@1 持平、MRR 小幅下降当成这个固定条件下的结果，不外推到 embedding 检索或完整 RAG。substring relevance 让标签更可审计，也会漏掉不含原字面的等价答案；下一轮若扩充查询，应同时复核同义表达、多答案段落和无答案问题。

样本规模也限制了小数点的意义。八问中一个排名移动就会明显改变均值，而角色文档对这些工程查询主要充当干扰项。更完整的评估需要增加真实用户问法、按主题分层，并让不同标注者复核答案片段。这里保留六位小数是为了结果文件稳定和逐次比较，不代表测量精度真的达到百万分之一。

至少现在，换一台机器或切到另一个工作树，实验仍从同一 commit 读取同一批 blob。脚本、查询、canonical 结果和可视化模板都进入版本库，图片只是这些证据的一个视图。参数有没有胜负可以继续争，复跑时用的语料与“什么才算答到”已经不再依赖我本机的一份临时文件。
