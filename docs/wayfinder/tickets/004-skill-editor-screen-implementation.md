---
id: 004
title: SKILL.md 编辑与预览双模页面实现
type: wayfinder:prototype
blocked_by: ["003"]
---

## Question
如何在 `AppRoute` 中接入 `SkillEditor(val skillId: String)` 路由，并实现一个兼具等宽代码输入框、错误阻断校验、以及复用 `StreamingGfmParser` 渲染效果的极简双 Tab 页面？
需解决：
1. 页面头部 MiuixScaffoldPage 标题与“保存”、“放弃”操作按钮；
2. 顶部 Segmented Control / Tab 切换“编辑”与“预览”模式；
3. 编辑区采用等宽字体（Monospace）与针对软键盘换行/滚动的平滑优化；
4. 预览区直接复用 Eta 内置的 GFM Markdown 引擎渲染正文。
