# Lyra 下一步架构（基于 AMP 0.1 + 当前代码盘点）

> 输入：AMP `docs/architecture.md`、`compaction.md`、`trust-chain.md`、`multi-agent.md`；
> Lyra 现状：9 个 core 模块 + app，153 个 Kotlin 文件（app 87 / core 66），服务层 40 个文件。
> 本文只回答一件事：**接下来把工程带到哪里，以及为什么是这些顺序。**

---

## 1. 现状盘点（已建成）

| 层 | 已实现 | 证据 |
|---|---|---|
| 协议对象 | Manifest 构建器、Frame（落库+digest）、Receipt（usage/ms/cost/VOI）、Handle、Event | `core:amp` 17 测试 |
| 注意力 | 显著性契约、焦点集/忽略集、预算五维、句柄分级 | `core:attention` 31 测试 |
| 凭据 | Claim/Hypothesis/Unknown 守门（W-1..W-5）、剪枝视图（P-1..P-4） | `WarrantLedger` 8 测试 |
| 账本 | 只追加 + 哈希链 + 压缩 checkpoint（P-5..P-7）+ 条件删除触发器 | 真机 556→100 |
| 僵局 | STALLED/UNVERIFIED/DRIFT 检测 + 会话终止 + 帧回填 | `ImpasseDetector` 7 测试 |
| 服务 | 40 个手机服务、`ServiceHub.catalog/describe/call`、风险分级 | `service/` 目录 |
| 聊天压缩 | 模型边界（ChatStore）+ 服务边界（ChatMessageService）条件压缩 | 7 测试 + 事件 |
| 指标 | VOI MAE / 浪费率 / 僵局率 / token / 金额 | 真机展示 |

**结论：单 Agent 的「可声明、可计价、可反驳、可审计」四件事已经闭环。** 下一阶段不是继续加对象，而是把已有的东西接成一条真正省注意力的生产线，并补两个安全缺口。

---

## 2. 四个结构性缺口

### 缺口 A：能力面有两套（最高优先级）

- 聊天侧：一个 `lyra_service` 工具 + 40 个服务的目录塞进 system prompt；
- Agent 侧：6 动词 + `read` + 动作。

后果：① 两套提示词各自演化；② 服务目录约 8–12KB 常驻，正是 AMP §5.8 说的「工具定义税」；③ Manifest 的 `capabilities` 声明与实际可用能力**没有绑定**。

### 缺口 B：凭据账本没接进真实运行

`WarrantLedger` 有完整规则，但 `AgentSession` 从不调用它：
- 没有 `guardIrreversible`，所以 **W-1 在真实路径上不生效**；
- 回执里没有 `claim_id` / `tier`，`tier_compliance` 指标算不出来。

### 缺口 C：签名是临时的

`Ed25519Signer` 在进程内 `generateKeyPair()`，**重启即换密钥**。Manifest 的 `signatures` 因此无法跨会话验证——`M-1` 名义上有、实质缺位。

### 缺口 D：指标没有时间维度

`metrics()` 每次从全量事件现算，没有历史快照，无法回答「这周比上周好还是差」。

---

## 3. 下一阶段架构：三条主线

### 主线一：统一能力面（Capability Plane）

```
ServiceHub ──┐
             ├──► CapabilityCatalog（唯一事实源）
Agent verbs ─┘        │
                      ├─ Manifest.capabilities（带 cost/tier/limits）
                      ├─ 模型可见的紧凑目录（按需展开，不常驻）
                      └─ policy_digest = sha256(catalog)
```

- 目录**逐字节稳定**，只在服务注册时变（对应 AMP R1 前缀缓存规则）；
- 模型默认只看到「能力摘要」（约 1KB），`attend(capability)` 才展开该服务的参数；
- Manifest 的能力列表由 catalog 自动派生，改服务即改 digest，可审计。

**验收**：prompt 体积下降 ≥50%，且 `tier_compliance` 仍为 100%。

### 主线二：凭据进循环（Warrant-in-the-loop）

```
act(action, ref) 前：
  claim = ledger.noteClaim(判断, tier, warrant)
  ledger.guardIrreversible(claim.id)     // W-1 真正生效
  receipt 带 claim_id / tier
```

- 不可逆动作（launch/set_text/支付类点击）必须 T2；
- 失败的动作自动把 claim 标 `contested`，下次不能静默复用（W-3）；
- 由此可算 `tier_compliance` 与「无据行动率」。

**验收**：100% 不可逆动作有 T2 证据；无据动作被拒并记录。

### 主线三：控制器闭环（Controller）

现在只有「选焦点 → 动作」，缺三个环节：

| 环节 | 现状 | 目标 |
|---|---|---|
| 模式 | 固定 `exploit` | explore/exploit/triage/reflect/rest 五态，按僵局切换 |
| 升级 | STALLED 直接终止 | STALLED → 换策略（滚动/返回）→ 仍失败才 ask |
| 剪枝进上下文 | 只做账本压缩 | Frame 的 `ignored` 按 P-1..P-4 剪枝后进 prompt |

**验收**：`impasse_rate` 下降且 `plan_success_rate` 不降。

---

## 4. 安全补丁（与主线并行，独立小改动）

1. **签名密钥持久化**：设备级 Ed25519 私钥存 Android Keystore（`KeyGenParameterSpec` + `setUserAuthenticationRequired(false)`），签名在 TEE 内完成；Manifest 的 `kid` 固定为设备 id。
2. **服务风险门禁**：`ServiceRisk` 与 `PolicyGuard` 映射表落地（SENSITIVE → 需同意、INPUT → 需确认、READ 直通），并在 `ServiceHub.call` 前置拦截。
3. **摘要固定**：服务目录在会话开始时快照，运行中若变化则拒绝调用（AMP §5.6 TOCTOU）。

---

## 5. 工程结构建议

| 现状 | 问题 | 建议 |
|---|---|---|
| `app` 87 个文件含 40 个服务 | app 层过厚，服务不可单测 | 抽 `:core:phone-services`，app 只留 UI 与装配 |
| `core:data` 依赖 `core:attention` | 存储层反向依赖注意力 | `Metrics` 留在 attention，data 只提供 `ReceiptSample` 查询 |
| 两套 SYSTEM_PROMPT | 内容漂移 | 抽 `:core:prompt` 提供统一能力契约片段 |
| 无集成测试 | 服务风险/权限没覆盖 | 加 `ServiceRiskGateTest`（纯 JVM）与一条 instrumented 冒烟 |

---

## 6. 实施顺序

| 阶段 | 内容 | 出口条件 |
|---|---|---|
| **S1** | 签名密钥持久化 + 服务风险门禁 | Manifest 跨重启可验；SENSITIVE 服务无同意不能调 |
| **S2** | CapabilityCatalog 统一能力面 | prompt ≤ 原来 50%；Manifest 自动派生 |
| **S3** | Warrant-in-the-loop | `tier_compliance=100%`，无据动作被拒并记录 |
| **S4** | 控制器模式与升级 | `impasse_rate` 下降 ≥30% |
| **S5** | 指标快照 + 趋势 | 可回答"本周 vs 上周" |

---

## 7. 一个需要你拍板的取舍

**要不要把「服务目录按需展开」做进 prompt？**

- 做：省 8–12KB 常驻 token，但要新增 `attend(service)` 展开语义，模型多一跳；
- 不做：实现简单，但每次对话都在付目录税。

我倾向做，因为服务还会继续变多（40 → 可能 80），而 AMP 的 R1（工具面不可变 + 按需披露）正是为这种情况设计的。

---

## 8. 不在这一阶段做的事

- 多 Agent / 联邦 / 他者模型：没有真实需求前不做，避免为协议而协议；
- 沙箱与多租户：个人自用设备不需要；
- 替换数据库：SQLite 目前够用，压缩层已解决增长问题。
