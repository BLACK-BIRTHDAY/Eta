# Domain Glossary (Eta)

## Skill
Agent 的模块化扩展能力单元。在文件系统上体现为一个独立目录，其核心入口与描述文件为 `SKILL.md`，包含元数据（YAML Frontmatter）与详细操作指引（Markdown 正文）。

## Slash Command (斜杠指令)
在对话输入栏以正斜杠 `/` 开头的指令交互模式。用于在聊天界面快速联想、选择并便捷调用对应的 Skill。

## Skill Candidate (技能候选项)
用户在输入栏键入 `/` 或后续搜索词时，系统从已启用技能集中检索出并呈现于输入框上方的匹配条目，包含技能图标、名称及摘要。

## Skill Editor (技能编辑器)
用于查看、编辑与实时预览 `SKILL.md` 内容的界面。具备纯文本源码编辑与渲染预览视图切换功能。

## Skill Override Layer (技能覆盖层)
针对系统内置技能（Built-in Skills）进行个性化修改时，在用户存储空间生成的覆盖副本。允许用户自定义内置技能行为，同时保留一键恢复为原始预置版本的能力。
