---
id: 002
title: 聊天栏 Slash 技能候选弹出卡片 UI 设计与焦点回填
type: wayfinder:prototype
blocked_by: ["001"]
---

## Question
如何利用 Miuix 设计语言构建优雅的悬浮候选列表卡片，并在点击候选项后平滑回填 `/<skill-name> ` 并保持键盘焦点？
需解决：
1. 弹出卡片在软键盘升起时的相对定位（输入容器正上方）；
2. 候选列表每项的视觉层级（技能图标、高亮名称、一行简要描述）；
3. 候选项点击时的文本替换逻辑（精准替换触发的 `/keyword` 为 `/<skill-id> `）并维持 TextFieldState 的光标和软键盘显示。
