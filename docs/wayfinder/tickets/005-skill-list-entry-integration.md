---
id: 005
title: 技能管理列表入口对接与恢复默认支持
type: wayfinder:task
blocked_by: ["004"]
---

## Question
如何在 `SkillSwitchRow` 的溢出菜单中挂载“编辑 SKILL.md”入口，并在内置技能已修改时提供“恢复默认”操作？
需解决：
1. 在更多菜单（`OverlayIconDropdownMenu`）中为所有技能添加“编辑 SKILL.md”条目，点击时触发路由跳转；
2. 检测内置技能是否已被覆盖修改，若已修改则在菜单中增加“恢复出厂设置/还原内置版本”选项；
3. 删除或重置覆盖版本后重新加载内置资产并刷新列表状态。
