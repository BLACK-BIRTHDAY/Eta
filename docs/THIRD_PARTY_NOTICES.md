# 第三方声明

## Lobe Icons

模型提供商列表中的品牌图标来自
[Lobe Icons](https://github.com/lobehub/lobe-icons) 的
`@lobehub/icons-static-avatar` 1.13.0。原始 1280×1280 WebP 素材在不改变颜色和比例的前提下，无损缩放为 128×128 后随 Eta 本地打包。

| Eta 资源 | Lobe Icons Avatar |
| --- | --- |
| `provider_logo_openai.webp` | `openai.webp` |
| `provider_logo_anthropic.webp` | `anthropic.webp` |
| `provider_logo_bailian.webp` | `bailian.webp` |
| `provider_logo_deepseek.webp` | `deepseek.webp` |
| `provider_logo_kimi.webp` | `kimi.webp` |
| `provider_logo_mimo.webp` | `xiaomimimo.webp` |
| `provider_logo_minimax.webp` | `minimax.webp` |
| `provider_logo_stepfun.webp` | `stepfun.webp` |
| `provider_logo_siliconflow.webp` | `siliconcloud.webp` |
| `provider_logo_openrouter.webp` | `openrouter.webp` |

Lobe Icons 使用 MIT License：

```text
MIT License

Copyright (c) 2023 LobeHub

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

OpenAI、Anthropic、阿里云百炼、DeepSeek、Kimi、Xiaomi MiMo、MiniMax、StepFun、硅基流动和 OpenRouter 的名称、图标及其他品牌标识归各自权利人所有。Eta 展示这些图标仅用于准确标识用户正在配置的模型服务，不表示这些厂商对 Eta 的赞助、认可或合作关系。OpenAI 图标的使用还应遵循其[品牌规范](https://openai.com/brand/)。

## 可选 APK 分析工具

Eta 不把下列工具打包进 APK。用户在 Linux 工具环境页面主动安装“APK 分析”时，Eta 从固定官方 Release 下载并校验制品；工具保存在 Eta 管理的 Alpine 环境中，适用各自许可证：

| 工具 | 来源 | 许可证 |
| --- | --- | --- |
| JADX | [skylot/jadx](https://github.com/skylot/jadx) | Apache License 2.0 |
| Apktool | [iBotPeaches/Apktool](https://github.com/iBotPeaches/Apktool) | Apache License 2.0 |
| smali / baksmali | [google/smali](https://github.com/google/smali) | BSD 3-Clause License |

JADX 的发行包许可证会随所需 CLI 文件一并保留；Apktool、smali 与 baksmali 的许可证和第三方声明保留在各自 JAR 制品中。Eta 仅提供经过校验的安装、命令入口和能力边界，不对这些工具重新授权。

GitHub 的实际制品域名不可达时，安装器可能依次通过 `ghfast.top` 和 `gh-proxy.com` 请求同一个公开 Release URL。代理会获知用户的网络地址及所请求的公开制品；Eta 不向代理发送账号、Cookie、API Key 或其他 Eta 数据，并在落盘前继续校验内置的官方制品大小与 SHA-256。不希望使用下载代理的用户可以不安装该可选档案。
