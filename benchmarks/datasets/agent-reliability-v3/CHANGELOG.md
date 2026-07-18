# Agent Reliability v3 变更记录

基线数据集：`agent-reliability-v2`，冻结提交 `2fc4a9bcd9793e86ec672dca97509e16bc32de05`。

## v3

- split 改为按 `topicKey` 分组：主题槽位 1–6 为 train、7–8 为 dev、9–10 为 test。
- `normalizedIntent` 移除样本序号，`templateFamily` 使用 split 专属措辞族，文档标识不得跨 split。
- 审核队列增加工具、文档、chunk、答案存在性、gold Evidence、引用与 claim 支持标签字段。
- 第二审核覆盖全部 135 条高风险样本，以及确定性抽取的 47 条普通样本。
- 所有候选标签重置为 `COLLECTED_UNREVIEWED`；v2 的审核状态与签署不得继承。

## 迁移修订 counter-terminal-v1

- 从已提交数据源 `ccb7efbf6c32947a09e57a740c1ee6f6a986ff1f` 迁移原有 370 条候选，不重新生成样本。
- 重写 27 条已废弃互动计数事实：12 条只读 Skill 候选和 15 条检索/生成候选。
- 样本 ID、快照 ID、分组切分与待审核状态保持不变；快照 ID 的原始生成规则不包含事实正文。
- 人工标签、审核人和签署仍为空，数据状态继续为 `COLLECTED_UNREVIEWED`。
