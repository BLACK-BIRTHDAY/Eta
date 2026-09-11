---
label: wayfinder:map
title: 聊天栏 Slash 技能联想与 SKILL.md 编辑器演进路线
---

## Destination
实现在聊天输入栏输入 `/` 时弹出已启用技能候选并便捷补全调用，以及在技能管理列表中支持对任何技能（含内置技能副本）的 `SKILL.md` 进行编辑、预览与保存，完成全链路工程落地与编译验证。

## Notes
- 核心规范与约束：Compose 原生极简实现（Ponytail 阶梯原则，避免引入外部重量级 AAR）、Miuix 质感组件生态对齐、双模切换（编辑/预览）。
- 依赖与关联文件：`AgentChatInputBar.kt`, `AgentChatBody.kt`, `AgentSkillsScreen.kt`, `SkillSwitchRow.kt`, `SkillEditorScreen.kt`, `AppRoute.kt`, `AgentAppState.kt`。
- 涉及领域概念：参考 `CONTEXT.md` 中的 `Skill`, `Slash Command`, `Skill Candidate`, `Skill Editor`, `Skill Override Layer`。

## Decisions so far
<!-- the index: one line per closed ticket, enough to judge relevance, then zoom the link for the detail the ticket holds -->

- [001: 聊天栏 Slash 指令状态管理与候选过滤契约](tickets/001-slash-command-autocomplete-state.md): 监听输入框状态，光标处于行首或空格后输入 `/` 激活联想，并基于已启用技能的 name/id/description 实时过滤。
- [002: 聊天栏 Slash 技能候选弹出卡片 UI 设计与焦点回填](tickets/002-slash-command-popup-ui.md): 构建 Miuix 磨砂悬浮卡片，点击条目平滑回填 `/<skill-id> ` 并保持输入焦点。
- [003: 内置与用户技能 SKILL.md 持久化与覆盖机制](tickets/003-skill-override-storage-mechanism.md): 编辑内置技能时生成覆盖副本，经 Frontmatter 校验后写入本地存储并触发实时热重载。
- [004: SKILL.md 编辑与预览双模页面实现](tickets/004-skill-editor-screen-implementation.md): 纯 Compose 原生打造 Miuix 双 Tab 编辑器，等宽源码编辑结合 Markdown 实时渲染。
- [005: 技能管理列表入口对接与恢复默认支持](tickets/005-skill-list-entry-integration.md): 在技能菜单中接入编辑入口，并在内置技能已修改时提供一键恢复默认版本。
- [006: 全功能端到端联动集成与编译验证](tickets/006-integration-build-verification.md): 串联聊天输入与技能管理全链路，通过全部单元测试与 assembleDebug 构建打包验证。

## Not yet specified
<!-- see "Fog of war": in-scope fog you can't ticket yet; graduates as the frontier advances -->
- 技能参数在 Slash 指令中的智能提示与动态补全校验（未来根据 SKILL.md frontmatter 中的 schema 进一步展开）
- 内置技能恢复出厂设置的细粒度差异对比（Diff 预览）

## Out of scope
<!-- see "Out of scope": work ruled beyond the destination; closed, never graduates -->
- 引入重量级外部 WebView / Monaco 代码编辑器
- 技能市场在线拉取与评分社区系统
