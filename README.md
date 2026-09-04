# Eta

**简体中文** | [English](README_EN.md)

<p>
  <img src="https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&amp;logoColor=white" alt="Kotlin 2.4.10">
  <img src="https://img.shields.io/badge/AGP-9.3.2-3DDC84?logo=android&amp;logoColor=white" alt="AGP 9.3.2">
  <img src="https://img.shields.io/badge/minSdk-34-3DDC84?logo=android&amp;logoColor=white" alt="minSdk 34">
  <img src="https://img.shields.io/badge/Gemini%203.x-Native%201M%20Context-4285F4?logo=google&amp;logoColor=white" alt="Gemini 3.x Native">
  <img src="https://img.shields.io/badge/Zero--Copy%20IPC-Pipe%202M%20Chars-FF6F00" alt="Zero-Copy IPC">
  <img src="https://img.shields.io/badge/Assistant%20Integrations-ColorOS%20%26%20HyperOS-1677FF" alt="Assistant integrations for ColorOS and HyperOS">
</p>

**面向 Android 的第三方系统级 AI Agent（KurumiTokizaki 专属增强版）**

Eta 借助 Root 与 LSPosed 越过 App 沙盒，直接进入系统底层：Hook 系统组件，接管电源键与厂商助手入口，读取原厂与第三方应用的私有数据。这些接近原厂、又比原厂更自由的能力，全部开放给你自己接入的模型（ChatGPT、Gemini 3.x、DeepSeek、Kimi 等，自带 API Key——BYOK）。

> [!TIP]
> 🌟 **本分支专属核心增强速览**
> 1. **💎 Google Gemini 3.x 原生协议全要素支持**：直接对接原生 `generateContent` 端点；内置彩钻标识，支持 1M 超大上下文 (`gemini-3.8-flash-tiered`)、原生自适应 Thinking 预算控制（`-1`/`0`/阶梯）、多模态生图与 `inlineData` Base64 实时 Markdown 图片渲染、官方 Key + 代理网关 Bearer 双重认证。
> 2. **⚡ 内核管道 Zero-Copy Pipe IPC**：彻底终结 Android 1MB Binder 事务物理限制。会话历史超 32KB 自动切换为 `ParcelFileDescriptor.createPipe()` 内核流式传输，支持高达 2,000,000 字符超长上下文；智能 `user` 轮次原子裁剪边界，杜绝断头 400 语法错误。
> 3. **💳 ColorOS 双击电源键直达钱包**：深度 Hook ColorOS 系统输入分发，双击电源键无论在锁屏还是亮屏状态，瞬间拉起 Google 钱包或一加钱包，支付出行一触即达。

---

- **系统 API 直达**：闹钟、媒体、音量、Wi‑Fi 等系统能力，模型可直接调用
- **个人上下文**：相册、日历、短信、通知、录音、健康摘要、ColorOS 系统记忆、QQ / 微信聊天图片等本机数据，模型按需读取
- **Google Gemini 原生支持**：原生接入 Google Gemini 3.x / GenAI 协议，支持百万级上下文、自适应 Thinking 思考预算、原生多模态生图与图片 Markdown 实时渲染
- **Zero-Copy Pipe 内核通信**：内核管道流式传输突破 Android 1MB Binder 物理限制，轻松承载 200 万字符超长上下文与原子级智能裁剪
- **内置浏览器**：后台加载网页、提取正文、操作页面元素，需要时可由用户直接接管
- **全新终端**：为移动设备重新设计的终端体验——常驻手动终端、多会话切换、交互式 PTY 控制台、可持久化守护任务、共享文件夹与文件浏览；Linux 环境在 Alpine 与 Debian 之间二选一
- **内置 Kimi Code**：Linux 环境预制 Kimi Code 安装，配合完美适配移动端的 Kimi Web UI 一键启动，手机上也能享受丝滑的 Vibe Coding
- **GUI Agent**：第三方 App 直接开放 API / CLI 才是最理想的路径，但移动互联网生态封闭，绝大多数应用没有任何机器接口；界面又是为人设计的，对模型天生不友好。没有接口的长尾场景，只能由 Agent 看屏幕、找控件、执行操作
- **系统快捷增强**：支持 ColorOS 双击电源键直接拉起 Google / 一加钱包，日常使用触手可得

其他第三方手机 Agent 面向大众用户，大众用户没有 Root 权限，能力只能做在 App 沙盒里，系统入口和数据仍属于厂商；桌面端的 Coding Agent（Codex、Claude Code）或 OpenClaw 被直接搬进手机时，功能再全，也只是一只困在沙盒里的龙虾，没有完整的系统环境，无法操作真正的 Android 设备；原厂助手则受自家生态约束，不会触碰第三方应用的数据。

Eta 可以看着屏幕、替你点一杯奶茶，但点屏幕不该是终点。能直达系统就不必模拟点击，这台手机在模型手里，就是一台可以使用的计算机。

手机里存放着你大部分的数据。在你的允许下，相册、通知、日程、便签、录音、位置、健康摘要与长期记忆一起成为上下文，Eta 做的事情会超出命令本身：它逐渐知道你在意什么、理解事情的前因后果。没有比手机更懂你的朋友，Eta 可以成为这样的朋友。亲近不意味着失去边界：每种能力都有独立开关，你选择模型，也决定它能看见什么、能做什么，以及什么时候停下来。

> [!NOTE]
> 完整能力需要 **Root** 与支持 libxposed API 102 的 **LSPosed**。App 本体不限 OPPO 或小米设备；ColorOS（小布助手）与 HyperOS（小爱同学）只是当前系统助手入口的适配范围。

## 界面预览

|                         GUI Agent                         |                         小布助手 BYOK：电源键启动                         |
| :-------------------------------------------------------: | :-----------------------------------------------------------------------: |
| <img src="docs/Screenshots/demo_gui_agent.gif" width="320" alt="GUI Agent"> | <img src="docs/Screenshots/demo_tools.gif" width="320" alt="小布助手 BYOK：电源键启动"> |

|              App 本体聊天首页              |                        小布：执行 Shell 命令                        |                       设备直达                       |
| :-----------------------------------------: | :-----------------------------------------------------------------: | :--------------------------------------------------: |
| ![聊天首页](docs/Screenshots/chat_home.jpg) | ![小布：执行 Shell 命令](docs/Screenshots/chat_breeno_analysis.jpg) | ![设备直达](docs/Screenshots/chat_device_direct.jpg) |

|                  设置                  |                工具能力                |                 Skills                 |
| :------------------------------------: | :-------------------------------------: | :------------------------------------: |
| ![设置](docs/Screenshots/settings.jpg) | ![工具能力](docs/Screenshots/tools.jpg) | ![Skills](docs/Screenshots/skills.jpg) |

## 核心能力

Agent 不会问一句答一句就结束：模型发指令，Eta 执行，结果写回上下文，模型再决定下一步，直到做完。四条执行路径可以组合使用：

| 路径                 | 说明                                                                                                                                                                                          |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **设备直达**   | 闹钟、计时器、媒体控制、音量、Wi‑Fi / 蓝牙、设备与存储状态等系统能力，以及相册、日历、联系人、短信、通知、健康摘要、ColorOS 便签与系统记忆等本机数据检索——全部是有明确 Schema 的结构化工具 |
| **网页浏览**   | 内置浏览器在后台加载 JavaScript 网页、提取结构化正文、操作页面元素；遇到验证码等场景可挂载到 App 界面，由用户直接接管                                                                         |
| **终端与文件** | 授权后执行`user` / `root` shell 命令、读写文件、运行脚本；面向用户的常驻终端提供多会话、交互式 PTY 控制台、守护任务、共享文件夹与文件浏览；Linux 环境在轻量 Alpine（musl）与兼容性更好的 Debian glibc 之间二选一，先安装基础环境，再按需安装工具；中国大陆网络优先使用单个实测较快的国内镜像，并保留官方源兜底 |
| **GUI 操作**   | 截图、无障碍节点、点击、滚动与输入；前台操作时显示浮层与手势反馈，可随时停止或接管。没有系统接口的长尾场景由它补齐                                                                            |

在此基础上：

- **内核管道跨进程通信（Zero-Copy Pipe IPC）**：突破 Android 1MB Binder 事务大小限制，当会话历史超过 32KB 时自动启用 `ParcelFileDescriptor.createPipe()` 流式传输，直通核心 Service；支持高达 2,000,000 字符超大上下文容量，以 `user` 轮次为最小原子边界智能裁剪，彻底杜绝孤立 `model`/`tool` 截断造成的 400 语法错误
- **长期记忆**：跨对话记忆保存在本机单一 `MEMORY.md`，按任务按需注入；设置页可查看用量、编辑、清空或关闭
- **Skills**：可浏览并安装公开 GitHub 仓库的 Skill，或导入本地 ZIP；模型按需读取，安装不会自动执行包内脚本
- **MCP 工具**：连接远程 Streamable HTTP 服务器，把用户逐项启用的第三方工具接入 Agent Loop；支持 HTTP / HTTPS 与可选 Bearer Token
- **会话与结果**：外部入口触发的运行结果归档到 App 会话，进程被杀也会尝试恢复；长按消息可复制、编辑或从该轮删除，最终回复可重新生成

## 独家底层技术解析

### 1. Zero-Copy Pipe 跨进程通信架构（击穿 1MB Binder 限制）

在 Android 系统的底层架构中，跨进程通信（IPC）普遍依赖 Binder 驱动。然而 Binder 驱动拥有著名的物理硬伤——**单个进程所有正在进行的事务共享仅约 1MB 的内存缓冲区**。

当 Agent 在手机上执行复杂的多步任务、累积了数十轮对话、或者输入包含超长终端执行日志与记忆上下文时，传统的 Binder Bundle 序列化极易触发致命的 `TransactionTooLargeException`，导致 Service 进程静默崩溃或断联。

**本增强版的破局方案：内核管道双轨传输机制**
- **双轨自适应**：消息序列化体量 $\le 32\text{KB}$ 时，继续走极速 Bundle 原生传输；一旦序列化字节超过 $32\text{KB}$，底层自动调用 Linux 内核管道 `ParcelFileDescriptor.createPipe()`。
- **零拷贝异步流传输**：客户端利用后台守护线程将 UTF-8 字节流写入管道写入端，并将管道读取端通过 Binder 句柄传给目标 Service；服务端通过 `ParcelFileDescriptor.AutoCloseInputStream` 直接流式消费并解码。
- **200 万超长容量**：会话上下文传输物理上限由原本的 9.6 万字符直接放宽至 **2,000,000 字符**，彻底释放现代大模型的长上下文威力。
- **原子轮次裁剪防 400**：传统裁切算法由于按单条消息削减，极易把对话截断在孤立的 `tool` 或 `model` 消息处，导致发起请求时被大模型 API 判定为非法轮次并返回 400 语法错误。本分支重构了裁剪逻辑，**强制以 `user` 轮次为最小原子边界**，若发生超限压缩，始终保留语法完备的上下文开端。

---

### 2. Google Gemini 3.x 原生协议全要素实战指南

本分支引入了与 OpenAI、Anthropic 并驾齐驱的 Google Gemini 官方原生渠道支持，带来极致的原生特性体验：

- **官方彩钻 Logo 与专属通道**：
  - 位于全局提供商第 2 顺位，原生直连 Google Gemini `v1beta/models/{model}:streamGenerateContent` 端点。
  - **双重智能鉴权**：请求官方端点（`generativelanguage.googleapis.com`）时，自动采用 `x-goog-api-key` 请求头（避免 Bearer 认证被 Google 判定为 `ACCESS_TOKEN_TYPE_UNSUPPORTED`）；请求第三方代理网关（如 One API / New API / 自建反代）时，自动双向附加 `Authorization: Bearer`，无缝兼容任何中转服务。
- **精选预置模型与前向通配**：
  - `gemini-3.8-flash-tiered`：主力对话与 Coding 模型，拥有 **1,048,576 (1M)** 顶级上下文窗口，全面支持多模态输入与原生 Thinking。
  - `gemini-3.1-flash-image` / `gemini-3-pro-image`：官方生图模型，自动加入白名单豁免，免遭文本对话过滤机制误杀。
  - **前向通配规则**：内置 `MODERN_GEMINI_REGEX = Regex("""^gemini-(?:[3-9]|\d{2,})\..*""")`，自动为后续面世的 Gemini 4.x / 5.x 赋予超长窗口与多模态特权，无需等待版本更新。
- **Google 原生 Thinking 思考预算支持**：
  - 支持自适应思考、关闭思考以及分级阶梯控制：
    - `自适应 (Default)`：预算传 `-1`，交由 Google 官方模型自主动态评估思考深度（推荐）。
    - `关闭 (Off)`：预算传 `0`，零等待极速输出。
    - `微念 / Low`：`4096` tokens
    - `斟酌 / Medium`：`16384` tokens
    - `沉思 / High`：`32768` tokens
    - `极致 / Max`：`65535` tokens（严格规避 65536 溢出报错）
- **多模态图像生成与 Markdown 实时渲染**：
  - 对生图模型返回的 `inlineData` 数据结构提供无缝解析，自动将原始 Base64 转换为标准 Markdown 图片语法嵌入消息流中，在聊天界面直接无缝展现精美画作。

---

### 3. ColorOS 双击电源键直达钱包

针对 ColorOS（OPPO / 一加）用户日常高频的刷卡与支付痛点，利用 LSPosed 深度 Hook 系统底层按键分发逻辑：
- 任意状态（**熄屏、锁屏密码界面、桌面或运行任意 App 期间**），只需双击电源键即可瞬间拉起 **Google 钱包** 或 **一加/欢太钱包**。
- 地铁闸机、公交刷卡、便利店闪付不再需要在桌面上翻找 App，抬手即可完成挥卡。

---

## 为移动设备重新设计的终端

Eta 把完整的计算环境装进手机：Android `user` / `root` Shell 之外，还可以安装 Alpine 或 Debian 用户态，全部统一在同一套为触摸操作重新设计的终端体验里。

- **常驻手动终端**：多会话并行、随时切换，会话独立于 Agent 任务存在
- **交互式 PTY 控制台**：真正的 TUI 体验，方向键、快捷键、滚动与 ANSI 渲染都可用
- **守护任务**：长任务退出页面后继续运行，日志随时回看
- **共享文件夹**：把任意 Android 目录挂载进 Linux 环境的 `/workspace/mounts/`，双向读写
- **文件浏览**：直接在 App 里浏览和预览 Linux 环境内的文件

### 内置 Kimi Code

- Linux 环境预制 Kimi Code 安装，开箱即用
- 首页一键启动完美适配移动端的 Kimi Web UI，随时随地继续 Coding 会话

## 使用场景

- **设备直达操作** — “明早 7 点设个闹钟”“暂停音乐”“把媒体音量调到 30%”，优先走结构化系统接口
- **理解最近的自己** — “我最近在忙什么”“这几天是不是总熬夜”“今天把时间花在哪了”，按需结合日程、通知、应用活动、健康摘要和本机记忆归纳
- **安排接下来的一天** — “结合明天的日程、地点和已有闹钟，告诉我几点出门，再补一个提醒”，先理解上下文，再把计划真正落到手机上
- **追踪正在发生的事** — “我的外卖到哪了”“取餐码是什么”“最近有什么快递”，从系统记忆和授权后积累的通知历史中寻找线索
- **找回散落的信息** — “上周那段录音里提到的书叫什么”“找出和这个地点有关的照片与便签”
- **聊天图片回顾** — “看看我最近的 QQ / 微信聊天图片，猜猜我在忙什么”
- **跨 App 操作与比价** — 处理应用里的待办项目，或截图分析淘宝商品、自动打开京东搜索同款；没有直达接口时才由 Agent 看屏幕、找按钮、执行
- **网页研究** — 在后台阅读 JavaScript 渲染的文档或资讯页面；遇到验证码时由用户直接接管
- **终端任务** — “清一下后台，查 LSPosed 日志看 Hook 有没有异常，再看看 Magisk 模块生效了没”
- **在手机上 Coding** — 打开内置终端使用预装的 Kimi Code，或从首页一键启动 Kimi Web，改代码、跑命令、提交推送全程在手机上完成
- **系统助手入口触发** — 从 Eta 系统助手面板、小布或超级小爱发起多步任务，交给同一套 Agent Runtime 执行

## 系统助手入口

### Eta 原生数字助理

Eta 通过 Android 标准 `VoiceInteractionService` 注册为可选数字助理：在设置页点击“Eta 系统助手”，再在 Android 的数字助理选择器中选中 Eta 即可。唤起后显示全屏助理面板并自动聚焦键盘输入框，支持流式回答、连续追问、取消与结果归档；可选择把唤起前的当前屏幕作为下一条消息的图片上下文。当前浮窗不提供语音识别或语音播报。

### ColorOS 电源键目标

在设置页的“系统助手接管”中可选择 ColorOS 长按电源键的目标：

| 目标     | 长按后的行为               | 默认助理自动设置        |
| -------- | -------------------------- | ----------------------- |
| 小布助手 | 保留 ColorOS 原始行为      | 不修改系统默认助理      |
| Gemini   | 沿用 Google 的系统助手链路 | 开关开启时切换为 Gemini |
| Eta      | 打开 Eta 全屏键盘助理浮窗  | 开关开启时切换为 Eta    |

新安装默认保持小布；旧版已经开启“长按电源键唤起 Gemini”的用户会继续使用 Gemini。“自动设置默认助理”是独立开关，只对 Gemini 和 Eta 生效。当前目标无法启动时，本次长按会立即回退到小布；HyperOS 电源键入口尚未接入。

### ColorOS 双击电源键直达钱包

基于 LSPosed / libxposed Hook 系统输入分发，支持在 ColorOS 下双击电源键快速呼出 Google 钱包或一加钱包。无论在锁屏还是亮屏状态，均可瞬间调出支付或交通卡，告别在桌面翻找 App 的繁琐操作。

### 小布与超级小爱

- **小布（ColorOS）**：接管小布对话入口，继承当前房间的文本上下文并解析图片输入，交给同一套 Agent Runtime 处理；支持 BYOK，默认只在 `/agent` 前缀下触发
- **超级小爱（HyperOS）**：支持文本与单张本地图片或截图，前缀、图片解析或任务入队任一前置检查失败时回到原生链路；已在 `7.13.32.0016`（`507013032`）通过真机验证

### Gemini 与一圈即搜（ColorOS）

这两项功能不依赖 ColorOS 原本提供的入口，由 Eta 创建或修复：

- **Gemini 解锁**：Google App 设备资格补齐、系统化、默认数字助理接管、电源键入口，以及锁屏/亮屏语音输入和息屏热词补偿
- **一圈即搜**：启用并修正原本不可用的 Android `contextual_search` 服务与 Google App 资格，再把手势条长按和双指识屏改造成触发入口，不改系统文件

Gemini 解锁与一圈即搜是 Eta 早期建立的 Google 能力解锁功能，目前不是开发重点，但仍会维护。

## 模型与 BYOK

- **模型协议**：支持 Google Gemini 原生 GenerateContent、OpenAI-compatible Chat Completions、Responses API 与 Anthropic Messages，全面覆盖 SSE 流式传输、工具调用（Function Calling）、多模态图片输入与深度推理；Responses 可展示推理摘要，并可按 Provider 开启服务端网页搜索
- **内置提供商**：OpenAI、Anthropic、**Google Gemini**、阿里百炼、DeepSeek、Kimi、MiMo、MiniMax、StepFun、硅基流动、OpenRouter
- **自定义提供商**：自定义 HTTP/HTTPS Base URL、API Key、请求头与 body JSON；HTTP 会明文传输 API Key、提示词与模型内容
- **模型管理**：内置官方目录、远程拉取、自定义模型与模糊搜索；可覆盖上下文长度与思考档位，本地覆盖始终优先于后续远程同步；各提供商分别记忆上次选择的模型
- **数据备份**：设置页可导出或导入对话、模型提供商配置与 `MEMORY.md`，用于更换包名或迁移设备；备份文件包含 Provider API Key，请妥善保管

BYOK（Bring Your Own Key）意味着 Agent 能力跟随你选择的模型，而不是被单一内置服务商限制。

## 安装

<details>
<summary><b>展开安装步骤</b></summary>

1. 安装 APK 并打开 Eta，配置模型提供商、API Key 和当前模型
2. 按需授予悬浮窗、无障碍、应用列表读取、位置、通知使用权、使用情况访问和后台运行等权限；如需从小布等后台入口执行位置任务，位置应授予“始终允许”
3. 按需开启设备直达、敏感信息读取、敏感设备操作和终端/文件工具；可在“上下文与扩展”中添加远程 MCP 服务器并逐项启用需要的工具；终端身份由用户明确选择为 `user` 或 `root`，Linux 发行版在 Alpine 与 Debian 中二选一，安装基础环境后再安装所需工具
4. 在系统设置中开启 Eta 无障碍服务
5. 可选系统入口：
   - Eta 原生数字助理：在设置页点击“Eta 系统助手”，并在 Android 系统选择器中将 Eta 设为默认数字助理
   - ColorOS 电源键切换、厂商助手接管、ColorOS 系统记忆、Gemini 与一圈即搜：在支持 libxposed API 102 的 LSPosed 环境中启用模块，按需勾选 `system`、SystemUI、Google App、小布识屏、小布助手、小布记忆和超级小爱作用域，然后重启手机

</details>

## 权限与安全

- 设备直达、敏感信息读取、敏感设备操作、终端/文件、网页浏览与记忆均为独立开关，当前默认开启；Runtime 每次执行前重新读取，用户可随时关闭
- 工具调用在执行前按模型看到的同一份 JSON Schema 校验参数；除此之外不增加权限确认、危险操作关键词、关键包/设置黑名单或文件允许根限制
- 短信验证码、Wi‑Fi 密码、通知正文、日志和个人数据检索结果只在当前回合提供给模型，不写入持久会话；通知历史仅在用户授予通知使用权后由 Eta 在本机保存最近 7 天，最多 1000 条
- MCP 工具默认关闭，并可整体停用服务器；HTTP / HTTPS 地址均可直接配置，Bearer Token 加密保存在 Android Keystore 中，工具参数与结果不写入持久会话
- 记忆读写同样只供当前回合使用，持久会话只保留脱敏操作摘要；聊天中引用的文件只把经过 Root 校验的路径写入模型上下文，不上传、不复制原文件
- 前台 GUI 操作显示运行浮层与手势反馈，用户可随时停止或接管

## 边界与限制

- **第三方集成限制**：Eta 无法获得原厂系统组件的全部私有权限，交互 UI、动画衔接和系统级一致性会弱于厂商内置助手
- **版本兼容性**：系统入口 Hook 强依赖 ROM、系统组件和目标 App 的具体实现，系统或 App 大版本更新后可能需要重新适配
