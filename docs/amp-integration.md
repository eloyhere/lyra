# AMP 接入说明

> 对端协议：[`R:\project\attention-manifest-protocol`](file:///R:/project/attention-manifest-protocol)（Attention Manifest Protocol v0.1）
> 实现：`core:amp`（纯 Kotlin，可 JVM 单测）

Lyra 把每一次「看屏幕 → 做动作」都写成 AMP 对象：**Manifest 声明自己是谁、Frame 记录这一轮注意了什么/忽略了什么、Receipt 结算这次动作花了多少**。

---

## 1. 对象映射

| AMP 对象 | Lyra 里的来源 | 写入时机 |
|---|---|---|
| `Manifest` | 能力声明（observe/tap/setText/scroll/launch）+ 硬约束 + 注意力预算 | 服务启动 / 能力变化时（待接） |
| `Frame` | 一次 `observe()`：目标、读屏工具、这一屏作为证据、被压缩掉的元素 | 每次观察 |
| `Receipt` | 一次动作：op、耗时、结果（success/failure/blocked） | 每次动作 |

一条典型轨迹：

```
Frame(f.1)  ← 打开外卖 App，屏幕 75 节点，送出 9 个元素，忽略 66 个（budget）
Receipt(rc.1, op=act, ms=180, outcome=success)   ← 点击搜索
Frame(f.2)  ← 新屏幕
Receipt(rc.2, op=act, outcome=blocked)           ← 命中支付按钮，被策略拒绝
```

`Frame.parent` 指向上一次观察，形成只追加的注意力链（X-1）。

---

## 2. 不变量怎么落地

| 不变量 | 实现点 | 测试 |
|---|---|---|
| **A-1 焦点有据** | `Slot` 的 `init` 强制 `why` 非空、`cost` 非空，缺失直接抛异常 | `slot 缺少 why 会被拒绝` |
| **A-2 忽略显式** | `FrameBuilder` 把压缩掉的节点写成 `Ignored(reason=budget, revisit_if=...)` | `frame 满足 A-1 A-2 A-3` |
| **A-3 预算刚性** | `Usage.exceeds(Budget)`；`Frame.violations()` 在写账本前检查 | 同上 |
| **A-5 模型提议宿主裁决** | 授权由 `ConsentManager`（宿主）决定，模型只能发起；拒绝会写 `Receipt(outcome=blocked)` | 策略测试 |
| **M-1 自述可验** | Manifest 的能力声明与实际执行共用同一套 `PolicyGuard` 常量 | `manifest 有 digest 与签名` |
| **X-1 只追加** | `AmpLedger` 只有 `append`，没有 update/delete；`resetForTest` 仅在测试用 | `ledger 只追加…` |

Digest：对"不含 digest 的规范化 JSON"取 SHA-256，字段顺序固定，保证同内容同摘要。

---

## 3. 代码结构

```
core/amp/
├── Json.kt          # 极简 JSON writer（不用 org.json / kotlinx.serialization）
├── AmpIds.kt        # id 规则 + sha256
├── AmpModels.kt     # Frame / Receipt / Slot / Budget … + violations()
├── FrameBuilder.kt  # Observation -> Frame
├── AmpLedger.kt     # 只追加账本 + StateFlow
└── Manifest.kt      # 能力/约束声明 + Ed25519 签名
```

`core:accessibility/AmpRecorder.kt` 负责把服务层的观察与动作喂给账本；Agent 循环落地后这个职责上移。

---

## 4. Manifest 长什么样

```json
{
  "amp": "0.1",
  "kind": "manifest",
  "agent": { "id": "a.lyra", "name": "Lyra", "version": "0.1.0", "role": "on-device phone operator" },
  "issued": "2026-10-24T07:00:00Z",
  "expires": "2026-10-25T07:00:00Z",
  "identity": {
    "constraints": [
      { "id": "c.no_payment", "statement": "永不执行支付、转账、免密支付等资金操作", "enforcement": "hard" },
      { "id": "c.consent_messaging", "statement": "发送消息前必须获得用户当场授权", "enforcement": "soft" }
    ]
  },
  "capabilities": [
    { "id": "automation.observe", "kind": "perception", "confidence": 0.95, "tier": "T1", "cost": { "ms": 30, "calls": 1 } }
  ],
  "attention": {
    "policy_digest": "sha256:<策略文本摘要>",
    "budget": { "tokens": 60000, "ms": 480000, "calls": 40, "interrupts": 2 }
  },
  "self": { "calibration": { "ece": 0.0 }, "limits": ["只知道当前屏幕，…"] },
  "digest": "sha256:…",
  "signatures": [{ "role": "self", "alg": "ed25519", "kid": "lyra#1", "sig": "…" }]
}
```

`attention.policy_digest` 是策略文本的摘要 —— 改策略就改摘要，可审计（这是把"承诺"接到"执行"上的钩子）。

---

## 5. 还没实现的部分

| 项 | 说明 |
|---|---|
| `Claim` / `Hypothesis` / `Unknown` | 需要 Agent 循环产出主张；当前只有注意层 |
| `Plan` / `Goal` | 任务尚未结构化，Frame 的 `goal` 暂时为空 |
| `social` / `inquiry` | 单人单机场景暂不需要 |
| Receipt 的 `predicted_voi` / `realized_voi` | 需要校准数据积累后再填 |
| 签名密钥持久化 | 当前 Ed25519 密钥对在内存，重启后 `kid` 不变但签名公钥会变；应落 Keystore |
| Manifest 定时刷新 | 目前只有 `build()`，还没有「过期前重签」的调度 |

---

## 6. 自检

```bash
./gradlew :core:amp:testDebugUnitTest
```

9 条用例：不变量、digest、slot 上限、why 强制、账本只追加、Manifest 签名、JSON 转义、id 规则、digest 稳定性。
