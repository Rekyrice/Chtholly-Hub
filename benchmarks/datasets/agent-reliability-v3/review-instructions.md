# Agent 评测候选人工审核说明

本目录中的 370 条记录均为机器生成候选，不是 gold。`READY_FOR_HUMAN` 只表示结构、空标签和占位文本检查通过。

主审核人应逐条核对输入是否自然、工具边界是否正确、相关文档与可接受 chunk 是否完整，并填写以下 JSON 字段：

- `acceptedSkillIds`、`requiredTools`、`optionalTools`、`forbiddenTools`；
- `relevantDocumentIds`、`acceptableChunks`、`allowedSources`；
- `expectedCitations`、`goldEvidence`、`claimSupportLabels`；
- `answerExists`（只能是 `true` 或 `false`）。

所有数组字段必须使用 JSON 数组文本，例如 `["page-explain"]`。`claimSupportLabels` 可以是 JSON 数组或对象。站内事实有答案时，引用、gold Evidence 和 claim 支持标签都不能为空；`no-answer` 样本不得填写引用或 gold Evidence。填写完成后再设置 `reviewer`、`reviewedAt` 和 `reviewerDecision=APPROVE`。

全部有状态写入样本、无答案、权限/提示词注入样本，以及固定抽取的 20% 普通样本必须由另一名真实人员完成第二审核。模型、Agent 或同一人员不得同时充当主审核人与第二审核人。

只有全部必填审核完成、分歧裁决完成、数据集提交哈希冻结后，维护者才可复制并填写 `signoff-template.json`。签署只确认标签来源和审核过程，不表示模型质量自动达标。
