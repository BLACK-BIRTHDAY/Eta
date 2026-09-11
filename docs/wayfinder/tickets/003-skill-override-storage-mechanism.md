---
id: 003
title: 内置与用户技能 SKILL.md 持久化与覆盖机制
type: wayfinder:task
blocked_by: []
---

## Question
当用户编辑内置技能或自定义技能时，文件系统如何组织写入路径？保存后如何触发 `SkillParser` 重新解析与 `SkillDao` 内存/数据库索引更新？
需明确：
1. 内置技能（原位于 assets 或预置目录）在用户编辑后，如何落盘到用户可写技能目录并生成覆盖副本；
2. 保存时的 YAML Frontmatter 基本合法性校验（是否包含必需的 name/description 等，避免因格式损毁导致技能解析崩溃）；
3. 保存后如何触发 `AgentAppState` / `SkillRuntime` 的实时热刷新与索引重载。
