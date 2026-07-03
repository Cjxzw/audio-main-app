# 小米 MiMo API 接入调研文档

> **文档用途**：小米 MiMo 开放平台 Token Plan 与 API 完整接入调研，作为移动端语音 Agent 项目的云端 LLM 备选方案参考。
>
> **创建日期**：2026-07-01
> **最后更新**：2026-07-01

---

## 一、文档与来源

| 文档类型 | 链接 |
|---------|------|
| Token Plan 订阅说明 | https://platform.xiaomimimo.com/static/docs/price/tokenplan/subscription.md |
| 模型与速率限制 | https://platform.xiaomimimo.com/static/docs/quick-start/model.md |
| 首次调用与兼容性 | https://platform.xiaomimimo.com/static/docs/quick-start/first-api-call.md |
| 工具集成总览 | https://platform.xiaomimimo.com/static/docs/integration/tools-overview.md |
| 错误码说明 | https://platform.xiaomimimo.com/static/docs/quick-start/error-codes.md |

---

## 二、Token Plan 概述

### 2.1 产品定位

MiMo Token Plan 是面向 **AI 编程场景** 的订阅资源包，可用额度在主流 AI 编程工具/框架里共享使用。

### 2.2 接入模式

| 协议 | 说明 |
|------|------|
| OpenAI 兼容协议 | 通过 `/v1/chat/completions` 调用，Header 为 `api-key` |
| Anthropic 兼容协议 | 通过 `/anthropic` 调用，Header 为 `x-api-key` + `anthropic-version` |

### 2.3 账号与密钥体系

| Key 类型 | 格式 | 计费方式 | 使用限制 |
|----------|------|----------|----------|
| **Token Plan Key** | `tp-xxxxx` | 套餐额度 | 仅订阅有效期内可用；限定编程工具/代理场景 |
| **按量付费 Key** | `sk-xxxxx` | 余额计费 | 无场景限制 |

> ⚠️ **重要**：两种 Key 完全独立，不能混用。Token Plan Key 必须配 Token Plan Base URL，否则返回 401。

---

## 三、套餐档位与额度

### 3.1 月付套餐

| 套餐 | 人民币 | 美元 | Credits |
|------|--------|------|---------|
| Lite | ¥39/月 | $6/月 | 4.1B |
| Standard | ¥99/月 | $16/月 | 11B |
| Pro | ¥329/月 | $50/月 | 38B |
| Max | ¥659/月 | $100/月 | 82B |

### 3.2 年付套餐（享 88 折）

| 套餐 | 人民币 | 美元 | Credits |
|------|--------|------|---------|
| Lite | ¥411.84/年 | $63.36/年 | 49.2B |
| Standard | ¥1045.44/年 | $168.96/年 | 132B |
| Pro | ¥3474.24/年 | $528/年 | 456B |
| Max | ¥6959.04/年 | $1056/年 | 984B |

### 3.3 折扣与计费规则

| 规则 | 说明 |
|------|------|
| 首次购买折扣 | 12% off |
| 连续年付 | 约 88 折 |
| 夜间折扣 | 北京时间 0:00-8:00，计费系数 0.8x |
| 额度用尽 | 立即停服，不会自动扣余额或 Bonus |
| 升级/降级 | 可升级（按差价补），不支持降级；过期后可重新购买 |

---

## 四、账号、认证与 API Key

### 4.1 登录与注册

- 使用小米账号登录开放平台（可提前在 https://id.mi.com 注册）
- 登录后进入 **Console-API Keys** 创建 API Key

### 4.2 API Key 安全须知

> ⚠️ **关键提醒**：订阅成功后 API Key 仅在订阅管理页可查看一次，务必先保存到安全位置。

---

## 五、接入方式与端点

### 5.1 OpenAI 兼容协议

#### 按量付费 Base URL

```
https://api.xiaomimimo.com/v1
```

#### Token Plan Base URL（按区域选择）

| 区域 | Base URL |
|------|----------|
| 中国（推荐） | `https://token-plan-cn.xiaomimimo.com/v1` |
| 新加坡 | `https://token-plan-sgp.xiaomimimo.com/v1` |
| 欧洲 | `https://token-plan-ams.xiaomimimo.com/v1` |

#### 请求头格式

```
api-key: tp-xxxxx
Content-Type: application/json
```

### 5.2 Anthropic 兼容协议

#### 按量付费 Base URL

```
https://api.xiaomimimo.com/anthropic
```

#### Token Plan Base URL

| 区域 | Base URL |
|------|----------|
| 中国 | `https://token-plan-cn.xiaomimimo.com/anthropic` |
| 新加坡 | `https://token-plan-sgp.xiaomimimo.com/anthropic` |
| 欧洲 | `https://token-plan-ams.xiaomimimo.com/anthropic` |

#### 请求头格式

```
x-api-key: tp-xxxxx
anthropic-version: 2023-06-01
```

---

## 六、模型清单与能力

### 6.1 文本生成模型

| 模型 | 能力 | 上下文 | 最大输出 |
|------|------|--------|----------|
| `mimo-v2.5-pro` | 深度推理、函数调用、结构化输出、联网搜索、流式输出 | 1M | 128K |
| `mimo-v2.5` | 多模态理解、深度推理、函数调用、结构化输出、联网搜索、流式输出 | 1M | 128K |

> 📌 **推荐**：`mimo-v2.5` 是当前主推旗舰，具备多模态理解能力；`mimo-v2.5-pro` 专注深度推理场景。

### 6.2 ASR（语音识别）

| 模型 | 能力 | 上下文 | 限速 |
|------|------|--------|------|
| `mimo-v2.5-asr` | 中英文、方言、Code-Switch、噪声环境、多说话人 | 8K | 100 RPM / 10K TPM |

- 可指定语言：`asr_options.language` 取 `auto`/`zh`/`en`（不设则自动检测）

### 6.3 TTS（语音合成）

| 模型 | 能力 | 说明 |
|------|------|------|
| `mimo-v2.5-tts` | 标准预设音色合成 | 默认推荐 |
| `mimo-v2.5-tts-voiceclone` | 音色克隆 | 需提供参考音频 |
| `mimo-v2.5-tts-voicedesign` | 一句话定义新音色 | 需在 `user` role 中描述 |

> 💰 **注意**：TTS 在 Token Plan 下限时免费，不消耗 Credits，但需确认活动是否仍在期。

### 6.4 旧版本状态

| 模型 | 状态 |
|------|------|
| `mimo-v2-pro` | 已于 2026-06-01 自动路由到 V2.5 |
| `mimo-v2-omni` | 已于 2026-06-01 自动路由到 V2.5 |
| `mimo-v2-tts` | 已于 2026-06-18 自动路由到 V2.5 |
| **MiMo-V2 全系** | **2026-06-30 00:00 正式下线，原模型名失效** |

---

## 七、模型选择决策树

```
┌─────────────────────────────────────────────────────────┐
│                    任务场景判断                           │
└────────────────────────┬────────────────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         │               │               │
         ▼               ▼               ▼
    纯文本对话       需要多模态        语音转文字
    (工具调用)      (图片理解)         (ASR)
         │               │               │
         ▼               ▼               ▼
┌─────────────┐  ┌─────────────┐  ┌─────────────────┐
│mimo-v2.5-pro│  │ mimo-v2.5   │  │ mimo-v2.5-asr   │
│(深度推理优先)│  │(多模态+联网)│  │ (中文识别优秀)   │
└─────────────┘  └─────────────┘  └─────────────────┘
                         │
                         ▼
                  文字转语音 (TTS)
                         │
         ┌───────────────┼───────────────┐
         │               │               │
         ▼               ▼               ▼
┌─────────────┐  ┌─────────────┐  ┌─────────────────┐
│mimo-v2.5-tts│  │mimo-v2.5-   │  │mimo-v2.5-       │
│(标准音色)   │  │tts-         │  │tts-             │
│             │  │voicedesign  │  │voiceclone       │
└─────────────┘  └─────────────┘  └─────────────────┘
```

---

## 八、mimo-v2.5 文本模型接入要点

### 8.1 基本参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `model` | string | `mimo-v2.5` 或 `mimo-v2.5-pro` |
| `messages` | array | 标准 OpenAI 消息格式 |
| `stream` | boolean | 是否流式输出 |
| `temperature` | float | 思考模式下强制为 1.0 |
| `top_p` | float | 思考模式下强制为 0.95 |
| `max_completion_tokens` | int | 最大输出 token 数（注意不是 max_tokens） |

### 8.2 思考模式（多轮工具调用）

- 启用深度推理时，后续轮次需回传历史 `reasoning_content`
- 思考模式下 `temperature` 和 `top_p` 会被强制覆盖

### 8.3 联网搜索

- 可搭配联网搜索功能
- **联网调用单独计费**，消耗额外 Credits

---

## 九、ASR 接入方式

### 9.1 Token Plan 计费特点

Token Plan 下 ASR **不按 Token 计费**，而是按输入音频时长统计，最终换算成小时计 Credits。

| 套餐 | 每月 ASR 额度参考 |
|------|-------------------|
| Lite (4.1B Credits) | 约 136.6 小时 |

### 9.2 请求格式

```json
{
  "model": "mimo-v2.5-asr",
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "type": "input_audio",
          "input_audio": {
            "url": "https://example.com/audio.mp3"
          }
        }
      ]
    }
  ],
  "asr_options": {
    "language": "zh"
  }
}
```

> ⚠️ **限制**：单次仅支持一段音频输入。

---

## 十、TTS 接入方式

### 10.1 三种模式对比

| 模型 | 用途 | 输入要求 |
|------|------|----------|
| `mimo-v2.5-tts` | 标准音色合成 | `assistant` role 放目标文本 |
| `mimo-v2.5-tts-voicedesign` | 自定义音色 | `user` role 描述音色 + `assistant` role 放目标文本 |
| `mimo-v2.5-tts-voiceclone` | 音色克隆 | 需提供参考音频 |

### 10.2 消息格式要求

```json
{
  "model": "mimo-v2.5-tts",
  "messages": [
    {
      "role": "user",
      "content": "Speak in a warm tone."
    },
    {
      "role": "assistant",
      "content": "你好，我是 MiMo，很高兴认识你。"
    }
  ],
  "audio": {}
}
```

> ⚠️ **关键**：合成文本必须放在 `assistant` role 消息中；`voicedesign` 模式额外要求 `user` role 消息描述音色。可使用 `optimize_text_preview=true` 省略 assistant 消息（预览模式）。

---

## 十一、流式输出

### 11.1 支持流式的模型

| 类别 | 支持的模型 |
|------|-----------|
| 文本 | `mimo-v2.5-pro`、`mimo-v2.5`、`mimo-v2-pro`、`mimo-v2-omni`、`mimo-v2-flash` |
| ASR/TTS | 官方文档未明确支持流式，优先按非流式调用；如必须流式需实测 SSE 分块返回 |

### 11.2 OpenAI 兼容流式调用

```bash
curl --location --request POST 'https://token-plan-cn.xiaomimimo.com/v1/chat/completions' \
--header 'api-key: tp-xxxxx' \
--header 'Content-Type: application/json' \
--data-raw '{
  "model": "mimo-v2.5-pro",
  "stream": true,
  "messages": [
    {"role": "user", "content": "Please introduce yourself."}
  ],
  "max_completion_tokens": 1024,
  "temperature": 1.0,
  "top_p": 0.95
}'
```

### 11.3 流式响应格式

流式响应使用 SSE（Server-Sent Events），每条消息以 `\n\n` 分隔，结束标记为 `data: [DONE]`。

---

## 十二、错误码速查

| HTTP 状态码 | 错误码 | 含义 | 处理建议 |
|-------------|--------|------|----------|
| 401 | `invalid_api_key` | API Key 无效或过期 | 检查 Key 是否正确、是否在有效期内 |
| 403 | `key_type_mismatch` | Key 类型与 Base URL 不匹配 | Token Plan Key 必须配 Token Plan Base URL |
| 429 | `rate_limit_exceeded` | 超出速率限制 | 降低请求频率，查看 RPM/TPM 配额 |
| 429 | `quota_exceeded` | 套餐额度用尽 | 升级套餐或等待下月重置 |
| 500 | `internal_error` | 服务端内部错误 | 稍后重试，持续出现联系平台 |

---

## 十三、响应格式参考

### 13.1 文本对话响应（非流式）

```json
{
  "id": "chatcmpl-xxx",
  "object": "chat.completion",
  "created": 1719000000,
  "model": "mimo-v2.5-pro",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "你好，我是 MiMo...",
        "reasoning_content": "..."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 42,
    "completion_tokens": 128,
    "total_tokens": 170
  }
}
```

### 13.2 流式响应（SSE data 片段）

```json
{
  "id": "chatcmpl-xxx",
  "object": "chat.completion.chunk",
  "created": 1719000000,
  "model": "mimo-v2.5-pro",
  "choices": [
    {
      "index": 0,
      "delta": {
        "content": "你好"
      },
      "finish_reason": null
    }
  ]
}
```

结束标记：`data: [DONE]`

---

## 十四、工具集成（Token Plan 共享额度）

### 14.1 已支持工具列表

MiMo Token Plan 官方已支持以下の工具（可共享套餐额度）：

| 工具 | 类型 | 备注 |
|------|------|------|
| OpenCode | 编程工具 | 共享额度 |
| OpenClaw | AI 代理框架 | 需手动改配置文件：删除 `"auth"` 字段，新增 provider |
| Claude Code | AI 编程助手 | 共享额度 |
| Kilo Code | VS Code 插件 | 共享额度 |
| Cline | AI 代理 | 共享额度 |
| Hermes Agent | AI 代理 | 共享额度 |
| CodeBuddy | AI 编程 | 共享额度 |
| Qwen Code | AI 编程 | 共享额度 |

### 14.2 工具配置要点

1. 按各工具文档配置专属 Base URL（见第五节）
2. 使用 `tp-xxxxx` 格式的 Token Plan Key
3. 部分工具功能可能受限（如 Cherry Studio 的 Agent 模式暂时不可用）

---

## 十五、计费与配额消耗估算

### 15.1 文本模型 Credits 消耗参考

| 操作 | 估算 Credits |
|------|-------------|
| 1K tokens 输入 | ~1K Credits |
| 1K tokens 输出 | ~3K Credits |
| 一次典型对话（1K 输入 + 500 输出） | ~2.5K Credits |

### 15.2 套餐能支撑多少对话？

| 套餐 | Credits | 估算可处理 Token 数（输入+输出） |
|------|---------|-------------------------------|
| Lite | 4.1B | 约 1B-2B tokens |
| Standard | 11B | 约 3B-5B tokens |
| Pro | 38B | 约 10B-18B tokens |
| Max | 82B | 约 20B-40B tokens |

### 15.3 并行消耗规则

- 不同模型（文本/ASR）**共用同一额度**，不是独立分桶
- TTS 限时免费，暂不消耗 Credits
- 联网搜索单独计费

---

## 十六、完整接入示例

### 16.1 文本对话（OpenAI，流式）

```bash
curl --location --request POST 'https://token-plan-cn.xiaomimimo.com/v1/chat/completions' \
--header 'api-key: tp-xxxxx' \
--header 'Content-Type: application/json' \
--data-raw '{
  "model": "mimo-v2.5-pro",
  "stream": true,
  "messages": [
    {"role": "user", "content": "Please introduce yourself."}
  ],
  "max_completion_tokens": 1024,
  "temperature": 1.0,
  "top_p": 0.95
}'
```

### 16.2 ASR（OpenAI 兼容）

```bash
curl --location --request POST 'https://token-plan-cn.xiaomimimo.com/v1/chat/completions' \
--header 'api-key: tp-xxxxx' \
--header 'Content-Type: application/json' \
--data-raw '{
  "model": "mimo-v2.5-asr",
  "messages": [
    {
      "role": "user",
      "content": [
        {"type": "input_audio", "input_audio": {"url": "https://example.com/audio.mp3"}}
      ]
    }
  ],
  "asr_options": {"language": "zh"}
}'
```

### 16.3 TTS（OpenAI 兼容）

```bash
curl --location --request POST 'https://token-plan-cn.xiaomimimo.com/v1/chat/completions' \
--header 'api-key: tp-xxxxx' \
--header 'Content-Type: application/json' \
--data-raw '{
  "model": "mimo-v2.5-tts",
  "messages": [
    {"role": "user", "content": "Speak in a warm tone."},
    {"role": "assistant", "content": "你好，我是 MiMo。"}
  ],
  "audio": {}
}'
```

### 16.4 Kotlin 接入示例（OpenAI 兼容）

本项目使用 OkHttp + Retrofit，可参考以下伪代码：

```kotlin
// Retrofit 接口定义
interface MiMoApi {
    @POST("chat/completions")
    suspend fun chatCompletions(
        @Header("api-key") apiKey: String,
        @Body request: ChatRequest
    ): Response<ChatResponse>
}

// 数据类（与 OpenAI 格式兼容）
@Serializable
data class ChatRequest(
    val model: String = "mimo-v2.5-pro",
    val messages: List<Message>,
    val stream: Boolean = false,
    val max_completion_tokens: Int = 1024,
    val temperature: Double = 1.0,
    val top_p: Double = 0.95
)

@Serializable
data class Message(
    val role: String,
    val content: String
)

@Serializable
data class ChatResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: Usage
)

@Serializable
data class Choice(
    val message: Message,
    val finish_reason: String
)

@Serializable
data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

// 调用示例
val api = Retrofit.Builder()
    .baseUrl("https://token-plan-cn.xiaomimimo.com/v1/")
    .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
    .client(OkHttpClient.Builder().build())
    .build()
    .create(MiMoApi::class.java)

val response = api.chatCompletions(
    apiKey = "tp-xxxxx",
    request = ChatRequest(
        messages = listOf(
            Message(role = "user", content = "你好")
        )
    )
)
```

> 📌 **注意**：流式响应需要用 OkHttp 的 `source` 手动解析 SSE 事件流。

---

## 十七、注意事项与坑点

| 风险点 | 说明 | 应对 |
|--------|------|------|
| **V2 系列已下线** | `mimo-v2-pro`/`mimo-v2-omni`/`mimo-v2-tts` 于 2026-06-30 下线 | 全部切到 V2.5 系列 |
| **Key 不要混用** | Token Plan 的 `tp-xxxxx` 只能配 Token Plan Base URL | 配置时仔细核对 Key 前缀 |
| **非 Coding 场景禁止** | 不要用 Token Plan Key 做自动化脚本、自建后端等 | 纯后端产品化场景走 `sk-xxxxx` 按量付费 |
| **工具兼容性差异** | 不同工具对 Token Plan 支持程度不同 | 先看对应工具文档再接入 |
| **ASR 单段限制** | ASR 单次仅单段音频 | 需自行分片处理长音频 |
| **TTS 角色限制** | 合成文本必须放在 `assistant` 消息 | 注意 `voicedesign` 模式需额外 `user` 消息 |
| **API Key 仅可查看一次** | 订阅成功后只在订阅管理页可复制 | 立即保存到安全位置 |
| **额度用尽即停** | 不会自动扣余额，直接停服 | 监控额度用量，设置告警 |
| **夜间折扣易错算** | 北京时间 0:00-8:00 系数 0.8x | 跨时段调用需分段计算成本 |

---

## 十八、与现有 LLM 方案对比

| 维度 | MiMo Token Plan | StepFun | OpenAI |
|------|----------------|---------|--------|
| **中文优秀度** | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| **工具调用** | ✅ 支持 | ✅ 支持 | ✅ 原生支持 |
| **多模态** | ✅ mimo-v2.5 | ✅ | ✅ GPT-4o |
| **流式输出** | ✅ 全部文本模型 | ✅ | ✅ |
| **ASR 能力** | ✅ 内置 mimo-v2.5-asr | 需单独接入 | Whisper API |
| **TTS 能力** | ✅ 内置，限时免费 | 需单独接入 | ✅ |
| **价格（入门）** | ¥39/月（Lite） | ¥20/万 tokens | $2.5/1M input |
| **区域节点** | 中国/新加坡/欧洲 | 中国 | 全球（延迟高） |
| **合规性** | 国内合规 | 国内合规 | 需考虑数据出境 |

---

## 十九、结论与建议

### 19.1 推荐策略

| 场景 | 推荐方案 |
|------|----------|
| **编程工具/代理场景（如 OpenClaw、Claude Code）** | 走 Token Plan，`tp-xxxxx` + Token Plan Base URL，性价比高 |
| **纯后端产品化接入（非编程工具）** | 走按量付费 `sk-xxxxx`，避免违反使用限制 |
| **原型验证/低成本测试** | Lite 套餐 ¥39/月，首购 12% off 后更低 |
| **生产环境（高并发）** | Pro 或 Max 套餐，配合夜间折扣调度批处理任务 |

### 19.2 下一步行动

1. **注册小米账号**：访问 https://id.mi.com 获取账号
2. **申请 API Key**：登录 https://platform.xiaomimimo.com 创建 Token Plan Key
3. **试用验证**：先用 Lite 套餐跑通文本对话 + 工具调用流程
4. **集成评估**：在 LangChain4j 中测试 MiMo API 的工具调用兼容性
5. **对比测试**：与现有 StepFun 方案做中文场景质量对比

---

## 二十、参考链接汇总

| 资源 | 链接 |
|------|------|
| MiMo 开放平台 | https://platform.xiaomimimo.com |
| 价格说明 | https://platform.xiaomimimo.com/static/docs/price/tokenplan/subscription.md |
| 模型清单 | https://platform.xiaomimimo.com/static/docs/quick-start/model.md |
| 首次调用 | https://platform.xiaomimimo.com/static/docs/quick-start/first-api-call.md |
| 工具集成 | https://platform.xiaomimimo.com/static/docs/integration/tools-overview.md |
| 错误码 | https://platform.xiaomimimo.com/static/docs/quick-start/error-codes.md |

---

> **文档结束。** 本文档记录了小米 MiMo Token Plan 的完整调研结果，可作为项目云端 LLM API 备选方案参考。建议与 StepFun 方案并行评估，根据实际中文场景质量测试结果决定最终选型。
