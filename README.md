# Lyra

手机上的生活助理：**说一句话，Agent 在真实 App 里把事办完。**

> 「帮我点一份麦当劳，送到公司」
> Lyra 打开美团 → 搜索 → 进店 → 加购 → 停在支付前，问你要不要付。

- 执行靠 **AccessibilityService**（读屏 + 手势），不是 root，也不是 ADB。
- 智能来自**用户自己的模型 API Key**，设备直连服务商，没有中转服务器，没有账号。
- 界面是 **Material 3 Expressive**（Compose）。
- 安全靠**引擎层硬边界**：资金 / 凭证永不执行，发消息 / 改文件必须人当场授权。
- 元认知用 **AMP 记账**：每轮注意了什么、忽略了什么、花了多少，可审计。

## 当前进度

| 模块 | 状态 |
|---|---|
| Compose + M3 Expressive 骨架（首页 / 聊天 / 终端 / 印象 / 用度 / 检查器 / 设置） | ✅ 真机可跑 |
| 主题系统：8 套固定配色 + 动态取色 | ✅ 8 条配色单测 |
| 离线语音识别：sherpa-onnx + SenseVoiceSmall | ✅ 真机解码验证（228 MB 按需下载） |
| 无障碍服务：快照 + 元素压缩 | ✅ 真机验证（设置页 75 节点 → 9 元素） |
| 自动化服务（AIDL）：observe / tap / setText / scroll / global / launch | ✅ 真机验证（服务点击生效） |
| 策略引擎（支付 / 凭证 / 自我 / 系统硬拒绝） | ✅ 10 条单测 |
| 授权闸门（发消息 / 文件 / 账号） | ✅ 代码 + 弹窗；生物识别待接 |
| AMP 账本（Frame / Receipt / Manifest / Event） | ✅ 协议 0.1 wire / 0.4.1 端口，1073 条单测，Frame + 事件链 Node 外部校验 |
| 模型网关 + Agent 循环 | ✅ LlmAgentBackend + 焦点帧落库；技能沉淀待后续 |
| 语音、技能沉淀 | ⏳ 后续里程碑 |

## 文档

| 文档 | 内容 |
|---|---|
| [自动化服务契约](docs/service-api.md) | AIDL 接口、观察 JSON、错误码、授权流程 |
| [安全模型](docs/security-model.md) | 三条硬边界、授权闸门、为什么确认框不能是按钮、威胁表 |
| [AMP 接入](docs/amp-integration.md) | Manifest / Frame / Receipt 映射、不变量落地 |
| [动态取色](docs/dynamic-color.md) | 壁纸主色 → CIELAB 色相 + Lyra 色阶映射、灰度回退、对比度约束 |
| [语音识别引擎](docs/asr-engines.md) | 端侧 SenseVoice 的接入、模型下载、隐私与回退；Qwen/系统方案对比 |

> 早期架构文档（分层、Agent 循环、技能蒸馏）在项目清空时一并丢失，待按当前实现重写。

## 一句话架构

```
语音 / 文字
   → Agent 循环（观察 → 决策 → 工具调用）
      → 策略闸门（支付拒绝 / 授权 / 编号新鲜 / 包黑名单）
         → 自动化服务（AIDL）→ 无障碍驱动 → 目标 App
   → 成功一次 → 蒸馏为技能 → 下次直接跑
```

四条不可让步的规则：

1. **Agent 只能通过白名单工具操作手机**，不能生成代码，不能直接调无障碍 API。
2. **花钱的动作永远不由 Agent 执行**，v1 只到「待支付」。
3. **授权只能由人完成**，且授权窗口 Agent 点不到（自身包拒绝 + 全局冻结 + 生物识别待接）。
4. **重复的事不该再花 token**：技能优先，失败才回退 Agent。

## 构建与测试

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
node tools/amp-conformance/validate-wire.mjs
```

要求：JDK 17+、Android SDK（`local.properties` 中的 `sdk.dir`）。

## 模块

`core:*` 已全部合并进 `:app`：源码统一在 `app/src/main/kotlin/com/eloykim/lyra` 下，
不再有独立 Gradle 模块（合并前分别是 core/model、policy、amp-protocol、agent、data、
network、attention、designsystem、accessibility）。原来的模块边界现在体现为包：

```
app/                       单 Activity + Compose 导航 + 授权弹窗；下面这些包都在 app 里
  com.eloykim.lyra.model/            UiNode / ScreenSnapshot（无 Android 依赖）
  com.eloykim.lyra.policy/           PolicyGuard：风险域判定（纯 Kotlin）
  com.eloykim.lyra.amp.protocol/     AMP 0.4.1 参考实现的 Kotlin 端口（wire 0.1，纯协议层）
  com.eloykim.lyra.agent/            AgentSession、焦点选择、Warrant、AMP Frame 适配
  com.eloykim.lyra.data/             SQLite 落库、只追加事件、canonical Frame payload
  com.eloykim.lyra.accessibility/    无障碍服务、快照、引擎、AIDL 服务、授权状态
  com.eloykim.lyra.automation/       AutomationService（AIDL 服务端）
  com.eloykim.lyra.designsystem/     Material 3 Expressive 主题与组件
  com.eloykim.lyra.network/          HttpClient / Llm 协议客户端
```

合并带来的两个构建项：`buildFeatures.aidl = true`（`app/src/main/aidl` 下的 IAutomation）
和 `testOptions.unitTests.isReturnDefaultValues = true`（原 amp-protocol 的单测依赖桩实现）。

