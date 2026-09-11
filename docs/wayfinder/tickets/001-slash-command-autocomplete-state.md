---
id: 001
title: 聊天栏 Slash 指令状态管理与候选过滤契约
type: wayfinder:task
blocked_by: []
---

## Question
如何在 `AgentChatInputBar` 中精准捕捉 `/` 触发条件、光标位置与联想词，并将已启用的技能集（`enabled == true`）高效过滤为联想建议流？
需明确输入状态机模型（如光标在首位或空格后输入 `/` 时激活联想态，在包含后续空格或换行时退出联想态），以及过滤匹配规则（基于技能 ID、名称和描述的前缀与模糊匹配）。
