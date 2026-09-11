---
id: 006
title: 全功能端到端联动集成与编译验证
type: wayfinder:task
blocked_by: ["002", "005"]
---

## Question
如何串联 Slash 快捷调用与编辑器链路，并通过 Gradle 构建与静态检查确保无运行时异常？
需验证：
1. 聊天界面输入 `/` 弹出技能列表，拼音/英文筛选正常，点击插入 `/<skill-id> ` 并维持焦点；
2. 技能管理界面能进入任何技能的 SKILL.md 编辑，编辑并保存后能够实时反映在技能详情与 Slash 候选中；
3. 执行 `./gradlew assembleDebug` 或 `testDebugUnitTest` 验证无类型错误与破坏性更改。
