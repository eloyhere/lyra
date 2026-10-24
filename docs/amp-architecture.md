# Lyra × AMP 0.1：架构设计

> 输入：`R:\project\attention-manifest-protocol`（v0.1-draft，2026-10，1695 行架构规范 + 14 个 schema + 参考运行时）
> 输出：Lyra 从「能点手机」升级为「可计价、可反驳、可审计」的注意力 Agent 的设计。
> 状态：设计稿。落地按 §15 分阶段，每阶段有可验收出口。

---

## 0. 一句话结论

Lyra 现在的 `core:amp` 只实现了 4 条不变量（A-1/A-2/A-3/X-1）的**日志格式**，缺的是 AMP 的核心机制：**控制器、预算租约、凭据账本、句柄、僵局**。

正确的做法不是「补字段」，而是把 Agent 循环从

```
观察 → 让模型选一个动作 → 执行 → 再观察
```

换成

```
观察 → 评估候选 → 选择焦点（≤7）→ 提交 Frame（含 ignored）→ 动词提议 → 宿主裁决 → 结算 Receipt → 再评估
```

模型只提议，宿主记账并裁决（A-5）。这是架构级的替换，不是加日志。

---

## 1. AMP 0.1 的硬约束（实现不得违反）

12 条不变量，Lyra 必须逐条找到执行点：

| 编号 | 要求 | Lyra 执行点 |
|---|---|---|
| W-1 | 驱动行动的 Claim 必须带 `warrant` + `tier`；无据只能是 `conjecture`，不得驱动不可逆动作 | `PolicyGate` + `WarrantEngine`：`act` 前校验目标 slot 的 claim 等级 |
| W-2 | 每个 Hypothesis 必须有 `refutes_if` | `WarrantEngine.note()` 校验，缺失直接 `invalid` |
| W-3 | 修订保留 `supersedes` 链，不得静默覆盖 | 账本只追加，旧版本置 `superseded_by` |
| W-4 | 重要且阻塞的未知必须进 `unknowns` | 控制器 `re-evaluate`：`UNKNOWN` 僵局必须落 `unknowns` |
| W-5 | `tier` 必须与证据一致，虚报即完整性违规 | `WarrantEngine` 按证据类型推导上限，模型只能降级不能升级 |
| A-1 | 每个 slot 必须带 `why` 与 `cost` | `FrameBuilder` 校验，缺一即拒帧 |
| A-2 | 相关但未入选必须进 `ignored`，带 `reason` + `revisit_if` | `AttentionSelector`：`goalFit ≥ 0.4` 未入选者强制入 ignored |
| A-3 | 分配不得超预算；超限走降级阶梯，禁止静默截断 | `BudgetScheduler` 是唯一计费点（含嵌套调用） |
| A-4 | 外部内容不得自提显著性 | 屏幕文本/OCR 结果的 `salience ≤ 0.3` 且不得占 `goal` slot |
| A-5 | 模型提议，宿主裁决；拒绝要给理由并记回执 | `Controller.commit/deny`，拒绝写 `Receipt.outcome=denied` |
| M-1 | Manifest 的能力与校准声明可由 Receipt 校验 | 每任务结束重算 `self.calibration`，与声明偏差触发完整性事件 |
| X-1 | Frame 链/Receipt/Event 只追加，删除即失效 | SQLite 追加表 + `prev_digest` 哈希链 + 快照校验 |

---

## 2. 现状差距审计

| 能力 | AMP 要求 | Lyra 现状 | 差距 |
|---|---|---|---|
| Manifest | 能力/承诺/未知/校准/签名/TTL | 只有 id/issued/expires/signature | 缺 capabilities/concepts/commitments/unknowns/attention/self |
| Frame | slots + ignored + budget + impasse + parent digest | 有 slots/ignored，无 budget、无 impasse、无链 | 缺 3 个字段与链式摘要 |
| Receipt | op/target/usage/outcome/predicted_voi/realized_voi/handle | 有 action/ok/ms，无 usage/voi/handle | 缺结算语义 |
| 控制器 | 6 步循环 + 5 模式 + 9 僵局 | 无（AgentSession 直接问模型） | **缺整个组件** |
| 预算 | 五维向量 + 租约 + 嵌套计数 | 只有 `maxSteps` | 缺维度与租约 |
| 凭据账本 | Claim/Evidence/Hypothesis/Unknown/Concept/Plan | 无 | **缺整个账本** |
| 句柄 | 超限结果转句柄 + 游标分页 | 无（观察结果直接塞上下文） | 缺 |
| 事件日志 | 只追加 + 哈希链 + 快照 | SQLite 可变行，无链 | 缺 X-1 |
| 动词面 | 6 动词 + read | 6 个动作工具（tap/input/scroll/launch/back/finish） | 语义不对齐 |

---

## 3. 目标架构

```
┌──────────────────────────── app (Compose) ────────────────────────────┐
│  Home · Settings · Inspector（帧链/回执/指标）· Consent Gate          │
└───────────────┬───────────────────────────────────────────────────────┘
                │ 只读状态 + 用户裁决
┌───────────────▼───────────────┐   ┌──────────────────────────────────┐
│  core:agent  AgentSession     │   │  core:attention   Controller     │
│  循环编排（不再直接调模型）    │──▶│  Appraise/Select/Commit/Settle    │
└───────────────┬───────────────┘   │  Budget · Lease · Salience        │
                │                   │  Impasse · Handles                │
                │                   └───────────┬──────────────────────┘
                │                               │ 读写
┌───────────────▼───────────────┐   ┌───────────▼──────────────────────┐
│  core:warrant  WarrantEngine  │   │  core:amp  协议对象 + 规范化 + 签名│
│  Claim/Hypothesis/Evidence    │   │  Manifest/Frame/Receipt/Verb      │
│  Tier 推导 · 修订链 · 未知     │   │  digest chain · schema 校验       │
└───────────────┬───────────────┘   └───────────┬──────────────────────┘
                │                               │ 追加写
                └───────────────┬───────────────┘
                     ┌──────────▼──────────┐
                     │  core:data  Ledger  │
                     │  SQLite 追加表 + 链  │
                     │  HandleStore（文件） │
                     └──────────┬──────────┘
                                │ 基座
                ┌───────────────▼──────────────────────────────┐
                │ core:accessibility  Substrate                │
                │ observe（节点+OCR 候选）· act · screenshot    │
                └──────────────────────────────────────────────┘
```

依赖方向单向：`agent → attention → amp → data`，`attention → warrant`，`agent → accessibility`（经 `AidlGateway`）。`amp` 不依赖 Android（纯 Kotlin，可单测）。

---

## 4. 对象映射：AMP → Android

| AMP 对象 | Lyra 对应 | 产生者 |
|---|---|---|
| `Manifest.capabilities` | 9 个动作能力（tap/setText/scroll/back/recents/launch/screenshot/ocr/files），每个带 `cost{ms,calls}` 与 `limits`（PolicyGuard 的硬边界） | `ManifestBuilder`（App 启动 + 能力变更时重建） |
| `Manifest.unknowns` | 当前任务阻塞项（如「不知道要选哪个收货地址」） | Controller `UNKNOWN` 僵局 |
| `Manifest.self.calibration` | 历史 `predicted_voi` vs `realized_voi` 的 ECE 与分桶 | 任务结束时从 Receipts 重算 |
| `Frame.slots` | 当前屏幕被注意的节点/OCR 块，每个带 `why`（为什么看它）与 `cost`（注入上下文约多少 token） | `FrameBuilder` |
| `Frame.ignored` | 因预算/范围/不可信被跳过的候选节点 | `AttentionSelector` |
| `Frame.impasse` | 当前卡住类型（STALLED/UNVERIFIED/DRIFT/…） | `ImpasseDetector` |
| `Receipt` | 每次 `act/attend/ask/note/plan/reflect` 的结算 | `Controller.settle` |
| `Receipt.handle` | 截图 / OCR 全量结果 / 长列表 → 内容寻址句柄 | `HandleStore` |
| `Claim` | 「这个按钮是提交」+ 证据（节点 text、OCR 行、上次结果） | `note` 动词 |
| `Hypothesis` | 「当前在结算页」+ `refutes_if`（出现「支付成功」文案） | `note` 动词 |
| `Unknown` | 「不知道优惠券是否已选」 | `ask` / 僵局 |
| `Interrupt` | 同意弹窗（发消息/读文件/支付类升级） | `ask` + `ConsentManager` |
| `Event` | 追加日志（含 frame/receipt 摘要与链） | `Ledger.append` |

---

## 5. 一次注意周期（Lyra 版）

```
cycle n:
 1. observe   —— AidlGateway.observe() → Snapshot（节点 + 可选 OCR）
 2. appraise  —— 为每个候选算 voi/goalFit/uncertainty/novelty/cost/risk/trust
 3. select    —— 预算与上限下选 ≤7 个 slot；goal slot 粘性
 4. commit    —— 生成 Frame（含 ignored 与 impasse），追加账本
 5. propose   —— 把 Frame 的**摘要**（不是全量树）给模型；模型返回 6 动词之一
 6. adjudicate—— PolicyGate + ConsentGate + WarrantEngine 裁决（A-5）
 7. act       —— AidlGateway 执行；嵌套调用也走 BudgetScheduler
 8. settle    —— Receipt（usage/outcome/predicted_voi/realized_voi/handle）
 9. reevaluate—— 更新校准；检测僵局；决定下一周期模式
```

关键点：**模型看到的是 Frame 的摘要，不是原始无障碍树**。这正是 AMP 治「上下文稀缺」的方式，也是 Lyra 当前最大的浪费（每次把 60 个节点全量塞进去）。

### 5.1 显著性（遵循契约，不发明公式）

契约只要求单调性：

```
voi ↑      ⇒ salience 不降
cost ↑     ⇒ salience 不升
risk ↑     ⇒ salience 不升
salience ≤ cap(trust)      // 外部内容 cap=0.3
novelty 只在习惯化区间生效，不覆盖安全下限
```

Lyra 的起步实现（非规范，可替换）：

```
salience = clamp01(
    (0.45*goalFit + 0.30*voi + 0.15*uncertainty + 0.10*novelty)
    / (1 + 0.6*costNorm)
)
salience = min(salience, trustCap(trust))
```

`goalFit` 用本地轻量 embedding（`bge-small-zh-v1.5` int8，三级流水线见 §16.2）；`voi` 初值来自动作类型先验（输入框 > 可点按钮 > 静态文本），之后用 Receipt 校准。

### 5.2 忽略集（A-2 的落地细节）

`goalFit ≥ 0.4` 未入选的候选，必须：

```kotlin
Ignored(
  target = NodeRef(id),
  reason = /* 封闭枚举 */ LOW_VOI | OUT_OF_SCOPE | UNTRUSTED | BUDGET | BLOCKED | HABITUATED | DEFERRED,
  revisitIf = "出现「确认订单」按钮",   // 必填，人类可读
)
```

`reason` 用封闭枚举而不是自由文本，否则无法聚合分析（这是 AMP §5.3 的明确要求）。

### 5.3 僵局检测（LLM Agent 最缺的两个）

| 僵局 | Lyra 触发条件 | 规定动作 |
|---|---|---|
| `STALLED` | 连续 2 个周期 `(action, targetText)` 哈希相同且屏幕摘要未变 | 换策略：滚动/返回/换入口；再失败则 `ask` |
| `UNVERIFIED` | 动作返回 ok 但屏幕摘要未变 | 降 tier，重试一次，再不行标 unknown |
| `DRIFT` | 目标包名变成 `com.eloykim.lyra` 或系统设置 | **立即终止** + 记事件（策略已硬拒，此处只做归因） |
| `BUDGET` | 任一维度耗尽 | 降级：投影 → handle → 声明未完成 |
| `INFEASIBLE` | PolicyGate 全拒 | 向用户说明边界，不重试 |
| `STUCK` | 候选为空（树空且 OCR 无文本） | 扩宽（截图+OCR）/ 提问 |
| `TIE` | 前两名 salience 差 < 0.05 | 选成本低者；仍平局则取树序靠前者 |

僵局**必须写日志**（AMP §3.6 明确要求：「一个从不记录卡住的系统，无法知道自己卡住了多少次」）。

---

## 6. 预算与计费

五维向量，Lyra 的可用近似：

| 维度 | Lyra 计量方式 |
|---|---|
| `tokens` | 事前估算（CJK `chars/1.7`，ASCII `chars/4`）；事后用厂商 `usage` 回填并校准，见 §16.1 |
| `ms` | 实测：observe/act/模型调用分别计时 |
| `calls` | 工具调用 + 模型调用 + OCR + 翻译，各自计数 |
| `interrupts` | 同意弹窗次数（P0 不计常规预算但必须记录） |
| `money` | 模型调用按 token 单价折算（catalog 里已有价格） |

**唯一计费点**：`BudgetScheduler`。任何嵌套（OCR 内部重试、翻译调用、AIDL 重绑）都必须经过它——AMP 引用 CVE-2026-114386 的教训：在沙箱内计费会被嵌套调用绕过。

**超限降级阶梯**（禁止静默截断）：

```
结果 ≤ 租约            → 直接注入
超租约但可投影          → 按字段投影（只留 text/bounds/clickable）
投影后仍超              → 转 Handle：注入摘要 + total + next_cursor
无法转句柄              → budget_exceeded（指明维度），由模型决定缩窄或放弃
```

---

## 7. 句柄

`HandleStore`：内容寻址，路径 `files/handles/<sha256>.json`，索引在 SQLite。

```kotlin
data class HandleRef(
  val id: String,            // h.<sha8>
  val kind: String,          // tree | ocr | list | image
  val tokens: Int,
  val scope: String,         // cycle | task | session
  val digest: String,        // sha256:...
  val cursor: String?,       // 分页游标
)
```

`read(handle, cursor, limit)` 是第 7 个动词，返回确定性分页（同一 cursor 永远同一页）。

---

## 8. 凭据层落地

### 8.1 Tier 推导（W-5：模型只能降级）

| 证据 | 允许的最高 tier |
|---|---|
| 无障碍节点属性（clickable/text） | T1 |
| OCR 文本 + 节点交叉验证 | T2 |
| 动作后屏幕变化确认 | T2 |
| 重复执行结果一致（≥2 次） | T3 |
| 用户口头确认 | T3 |
| 无证据的模型断言 | T0（`conjecture`，禁止驱动不可逆动作） |

### 8.2 不可逆动作的守门（W-1）

`act` 前检查：

```
if action.isIrreversible and slot.claim.tier < T2:
    deny(reason = "evidence_insufficient", 建议 = 先 note 或 ask)
```

Lyra 的不可逆动作：`launch`（会切前台）、`setText`（可能触发提交）、`tap`（在支付/提交按钮上）、`scroll`（会丢位置，弱不可逆）。

### 8.3 未知（W-4）

`Unknown` 只有两种来源：`ask` 被拒/超时，以及 `UNKNOWN` 僵局。**不允许模型沉默**：控制器在每个 `reflect` 周期检查「阻塞且未记录」的未知，缺失则补记。

---

## 9. 打断与同意（Lyra 的关键差异点）

同意弹窗不是「一个对话框」，是 **Interrupt**，要记账：

| 级别 | Lyra 场景 | 预算 |
|---|---|---|
| P0 安全 | 支付/凭证/自我提权 | 不计常规预算，但必记；直接拒绝（不是打断） |
| P1 承诺 | 用户已设定的任务截止 | ≤3/天 |
| P2 机会 | 发消息、读文件、剪贴板 | ≤1/小时，可合并 |
| P3 好奇 | 「要不要顺便…」 | 0，只入队 |
| P4 静默 | 日志 | 0 |

**重复询问单独计价**（AMP 引用：每条额外提醒接受率下降约 30%）——同一 `(action, target)` 的第二次询问必须降级为「沿用上次决定或放弃」，不能反复弹窗。

`attention_debt` = 被延后/拒绝的请求按年龄加权和，作为可见指标暴露在 Inspector。

---

## 10. 持久化（X-1 的落地）

SQLite v5 新增表：

```sql
-- 追加式事件日志（唯一事实来源）
CREATE TABLE events (
  seq         INTEGER PRIMARY KEY AUTOINCREMENT,
  at          INTEGER NOT NULL,
  actor       TEXT NOT NULL,          -- controller | model | user | policy
  type        TEXT NOT NULL,          -- frame | receipt | claim | interrupt | impasse | manifest
  refs        TEXT,                   -- JSON array of ids
  payload     TEXT NOT NULL,          -- 规范化 JSON
  digest      TEXT NOT NULL,          -- sha256(canonical(payload))
  prev_digest TEXT,                   -- 链
  org_run_id  TEXT
);

CREATE TABLE frames (
  id TEXT PRIMARY KEY, run_id TEXT, cycle INTEGER, parent TEXT,
  mode TEXT, goal TEXT, digest TEXT, payload TEXT, at INTEGER
);

CREATE TABLE receipts (
  id TEXT PRIMARY KEY, run_id TEXT, frame_id TEXT, op TEXT, target TEXT,
  usage TEXT, outcome TEXT, ms INTEGER, predicted_voi REAL, realized_voi REAL,
  handle TEXT, at INTEGER
);

CREATE TABLE claims (
  id TEXT PRIMARY KEY, run_id TEXT, subject TEXT, predicate TEXT, object TEXT,
  warrant TEXT, tier TEXT, confidence REAL, refutes_if TEXT,
  superseded_by TEXT, at INTEGER
);

CREATE TABLE unknowns (
  id TEXT PRIMARY KEY, run_id TEXT, question TEXT, importance REAL,
  blocking INTEGER, resolved_by TEXT, at INTEGER
);

CREATE TABLE handles (
  id TEXT PRIMARY KEY, kind TEXT, digest TEXT, path TEXT, tokens INTEGER,
  scope TEXT, created_at INTEGER
);
```

`events` 表只允许 `INSERT`；任何 UPDATE/DELETE 由 `Ledger` 层拒绝并在 `SQLiteOpenHelper` 外封装（DB 层面加触发器 `RAISE(ABORT)`）。

**快照**：每 20 个 frame 或任务结束，写 `ledger-snapshot`（含 `seq`/`digest`/`prev`），校验时可检测重排与截断。

---

## 11. 动词面改造

模型看到的工具从 6 个动作改为 **6 动词 + read**（总 schema 目标 < 450 tokens）：

| 动词 | Lyra 参数 |
|---|---|
| `attend(target, why, budget?)` | target = node id / region / handle；返回该 slot 的展开内容 |
| `act(action, budget)` | action ∈ {tap, setText, scroll, back, recents, launch, screenshot, ocr}，内部映射到 AIDL |
| `ask(question, to?, class?)` | 触发 P1–P3 打断；`class ∈ {clarify, permission, confirm, choice}` |
| `note(assertion, evidence?, tier?)` | 写 Claim/Hypothesis；tier 只能降 |
| `plan(goal, tasks?, process?)` | 写 Plan 草稿；Planner 校验环与判据 |
| `reflect(scope?)` | 返回校准摘要、未知、建议修订 |
| `read(handle, cursor, limit?)` | 句柄分页 |

**动作工具不再直连模型**是这次改造的实质：模型说「点这个」，控制器决定「能不能点、花多少、记什么」。

---

## 12. 指标（Inspector 看板）

| 指标 | 定义 | 目标 |
|---|---|---|
| `wasted_attention_ratio` | 花在 `realized_voi < 0.2` 的 slot 上的 tokens / 总 tokens | 逐版本下降 |
| `tier_compliance` | 不可逆动作中 tier ≥ T2 的比例 | 100% |
| `voi_mae` | `|predicted−realized|` 的滑动均值 | < 0.25，超标则降级到 goalFit 排序 |
| `interrupt_rate` | 每小时打断次数 / 用户设定上限 | ≤ 上限 |
| `impasse_rate` | 每任务僵局数 / 步数 | 用于回归对比 |
| `frame_utilization` | 被模型实际 attend 的 slot / 总 slot | 低说明选错焦点 |

---

## 13. 安全不变量映射

| 威胁 | AMP 机制 | Lyra 现有 | 需要补 |
|---|---|---|---|
| 提示注入（屏幕文本当指令） | A-4 不可自授 + 数据化渲染 | 部分（prompt 里声明） | 显著性 cap、来源标注、外部内容 15% 预算上限 |
| 自我提权（改设置/自授权） | DRIFT + Policy 硬拒 | 已有 debug 开关外的硬拒 | 归因事件（DRIFT 记日志） |
| 静默截断导致自信错误 | A-3 降级阶梯 | 有 `truncated` 标记 | 句柄 + 游标 |
| 伪造能力声明 | M-1 | 无 | 校准重算 + 完整性事件 |
| 日志被改 | X-1 | 无 | 哈希链 + 快照 |

---

## 14. 需要改的文件（按依赖顺序）

| 顺序 | 模块 | 文件 | 改动 |
|---|---|---|---|
| 1 | `core:amp` | `AmpModels.kt` | 升级到 0.1：Frame 加 budget/impasse/parent、Receipt 加 usage/voi/handle、Manifest 全字段 |
| 2 | `core:amp` | `Canonical.kt`（新） | JCS 规范化 + sha256（对齐参考实现 canonical.ts） |
| 3 | `core:warrant`（新模块） | `WarrantEngine.kt` | tier 推导、warrant 校验、修订链、unknowns |
| 4 | `core:attention`（新模块） | `Controller.kt` `Budget.kt` `Salience.kt` `Impasse.kt` `Handles.kt` | 六步循环、五维预算、显著性契约、僵局、句柄 |
| 5 | `core:data` | `LyraDatabase.kt` v5 + `Ledger.kt` | 追加表、哈希链、快照、触发器防改 |
| 6 | `core:agent` | `AgentSession.kt` `LlmAgentBackend.kt` | 改用动词面；模型只见 Frame 摘要 |
| 7 | `core:accessibility` | `SnapshotBuilder` `AutomationEngine` | 输出候选特征（cost/trust/risk）；act 返回结构化 receipt 数据 |
| 8 | `app` | `InspectorScreen.kt`（新） `SettingsScreen.kt` | 帧链/回执/指标看板；AMP 开关与预算设置 |

---

## 15. 迁移路线（对齐 AMP §14.2）

### P0 计量（1–2 周）
- Receipt 全覆盖：每次动作有 `usage`/`outcome`/`ms`；
- 追加事件日志 + 哈希链；
- 看板能回答「钱花在哪」。
- **出口**：任意一次任务可回放完整事件序列。

### P1 注意力（2–4 周）
- Controller + Frame + 忽略集 + 显著性契约；
- 预算租约与降级阶梯；
- 模型只见 Frame 摘要。
- **出口**：`wasted_attention_ratio` 比 P0 下降 ≥30%；V-9（预算）通过。

### P2 凭据（3–4 周）
- tier 推导、warrant、修订、unknowns；
- 不可逆动作 tier 守门。
- **出口**：`tier_compliance = 100%`。

### P3 计划（可选）
- Plan 草稿、承诺窗口、放弃与回滚。

### P4 社会 / P5 联邦（可选）
- 他者模型、注意力协商；跨设备 bundle 导入导出（签名已具备基础）。

---

## 16. 已定决策与未决问题

### 16.1 已定：usage 回填（token 计量）

设备侧预算是**事前**估算，回执是**事后**结算，两者必须闭环：

```
事前：budget.tokens_est = f(chars)          // 用于租约发放，宁可高估
事后：receipt.usage = { prompt_tokens, completion_tokens, calls, ms }
回填：receipt.estimated = false；同时记录 tokens_est 与真实值之比 r = real/est
校准：按 (provider, model) 维护 r 的滑动中位数，下一次预算用 tokens_est * r
```

实现要点：

- 适配层把厂商返回的 `usage` 原样带上（OpenAI/DeepSeek `prompt_tokens`、Anthropic `input_tokens`、Gemini `promptTokenCount`），已在 `core:network` 的 `LlmUsage` 里具备；
- 估算器只用于「发请求前决定给多少租约」，不参与结算；
- 当 `usage` 缺失（流式中断、错误）时保留 `estimated=true`，并在指标里单列，不混入校准样本；
- `money` 维度用真实 usage × catalog 单价计算。

### 16.2 已定：goalFit 用本地轻量 embedding

**选型结论：`bge-small-zh-v1.5`（int8 量化，512 维）+ ONNX Runtime Mobile。**

理由（目标是中文 UI 短文本的语义匹配，不是通用检索）：

| 候选 | 维度 | 体积(int8) | 中文短文本 | 结论 |
|---|---|---|---|---|
| **bge-small-zh-v1.5** | 512 | ≈24 MB | 强（中文语料训练） | **采用** |
| multilingual-e5-small | 384 | ≈30 MB | 中（多语言摊薄） | 备选（要多语时） |
| all-MiniLM-L6-v2 | 384 | ≈6 MB | 弱 | 否 |
| USE multilingual (MediaPipe) | 512 | ≈6 MB | 一般，社区反馈质量不稳[^1] | 否 |

[^1]: MediaPipe 文本嵌入的社区反馈（质量不稳定）：<https://discuss.ai.google.dev/t/bad-quality-of-text-embeddings-with-mediapipe/96013>

**运行时：ONNX Runtime Mobile**（AAR 约 5 MB），不用 TFLite——
bge 系列官方权重是 PyTorch/ONNX，转 TFLite 要额外做算子适配，而 ORT 直接吃 int8 ONNX。

**性能预算**（中端机 CPU，4 线程）：

| 环节 | 耗时 |
|---|---|
| 单条短文本（≤32 token） | 4–8 ms |
| 一次帧的候选（先粗筛到 ≤20 条） | 80–160 ms |
| 命中缓存（UI 文案高度重复） | ≈0 ms |

**三级流水线**（保证不拖慢主循环）：

```
候选节点文本
  ├─ L1 缓存：LRU(text → vec)，命中率预期 70–90%（同一界面的按钮反复出现）
  ├─ L2 词法粗筛：字符 2-gram Jaccard + 动作类型先验，取 top-20
  └─ L3 语义：bge-small-zh 编码，余弦相似度 → 校准成 goalFit ∈ [0,1]
```

**降级**：模型未下载/加载失败 → 只用 L1+L2（词法 + 先验），并在 Frame 里标记 `goalFit_source=lexical`，指标里区分，避免把两种质量混在一起比较。

**分发**：24 MB 不塞进 APK，首次启动后台下载到 `files/models/`，校验 SHA-256；下载完成前用词法降级。这与「离线可用」不冲突——模型一旦落地即完全离线。

**校准**：`goalFit` 是相似度的单调映射，不直接用余弦值。用 100 条人工标注的（任务描述，UI 文本）对拟合分段线性映射，避免"相似度 0.8 但毫不相关"的假阳性进入焦点集。

### 16.3 未决

1. **句柄与截图**：截图是像素证据，OCR 是文本证据，二者是否要合成一个 handle 还是分开？
2. **多设备/多 Agent**：联邦已可执行，手机 + 本地服务是否需要跨设备审计？若需要，Manifest 的 agent id 与签名密钥要按设备派生。
3. **调试开关与 DRIFT**：debug 允许操作 Lyra 自身会与 DRIFT 归因冲突；建议 debug 下把 DRIFT 降为 warning 但仍记录。
4. **多设备/多 Agent**：联邦已可执行，但 Lyra 是否需要跨设备审计（手机 + 本地服务）？若需要，Manifest 的 agent id 与签名密钥要按设备派生。
5. **调试开关与 DRIFT**：当前 debug 允许操作 Lyra 自身，会与 DRIFT 归因冲突；建议 debug 下把 DRIFT 降级为 warning 但仍记录。

---

## 附录：与现有代码的兼容性

- `PolicyGuard` 的硬边界直接成为 `Manifest.values/limits` 与 `INFEASIBLE` 僵局的来源，不改语义；
- `ConsentManager` 的 pending 状态映射为 `Interrupt`，`isPending` 期间所有动作暂停的行为保留（等价于 A-5 的「宿主可拒绝」）；
- `AmpRecorder`（现有）保留为 `Ledger` 的兼容入口，逐步把调用点从「记录字符串」换成「写 Receipt」。
---

## 17. 线程模型

三类工作，三种执行体，互不借用：

| 执行体 | 线程 | 承载 | 理由 |
|---|---|---|---|
| `AppGraph.networkPool` | 固定 4 线程 | 模型 HTTP 调用、模型目录刷新、翻译 | IO 密集，可并发；已有实现 |
| `LocalModelPool.dispatcher` | **单线程**，后台优先级 | embedding / 本地推理 | 见下 |
| 主线程 | — | Compose 渲染、事件分发 | 任何 >2ms 的工作不得在其上 |

### 17.1 本地模型为什么是「独立单线程」而不是线程池

1. **ORT session 并发会互相争抢**：单个 session 内部已有 intra-op 线程池，多个 `run()` 并发进入只会加剧竞争，吞吐不升反降；
2. **内存**：模型常驻 24MB 权重 + 每线程 activation 缓冲，线程越多峰值越高；
3. **语义上天生可串行**：每一帧只需要「当前这一帧」的打分，旧帧结果没有价值；
4. **合并优于排队**：用 `MutableStateFlow<FrameCandidate>` + `collectLatest`，新帧到来时取消未完成的计算，队列永远不会堆积。

### 17.2 边界规则

- 本地推理**不占** `networkPool`：否则一次 embedding 卡住会阻塞模型调用；
- 翻译服务是 HTTP 调用，走 `networkPool`，不走本地池；
- embedding 的耗时计入预算 `ms` 维度（A-3），并在 `Receipt` 里单列 `embedding_ms`；
- 模型未就绪时立即返回词法结果，`goalFit_source=lexical`，**不允许等待下载**；
- 任何本地推理都必须可取消（`ensureActive()`），用户切走页面即取消。

### 17.3 当前实现

| 文件 | 内容 |
|---|---|
| `core/attention/LocalModelPool.kt` | 单线程调度器（daemon，后台优先级） |
| `core/attention/GoalFit.kt` | `TextEmbedder` 接口 + `LexicalEmbedder`（2-gram 哈希基线）+ `GoalFitScorer`（LRU 缓存 + 余弦 + 分段校准） |
| `core/attention/GoalFitTest.kt` | 6 条单测：相关性排序、无关归零、空输入、缓存一致性、分词稳定性 |

`bge-small-zh` 接入时只需实现 `TextEmbedder`，`GoalFitScorer` 与调度器不变。
---

## 18. 调试夹具（debug 构建专用）

真机自动化点按钮经常受输入法、坐标、Activity 栈影响，所以调试入口走 Intent 而不是 UI：

```bash
adb shell am start -n com.eloykim.lyra/.MainActivity \
  -e task "读取当前屏幕的前 20 条内容" \
  -e delay 6000 \
  -e tokens 1500 \
  -f 0x20000000
# 2 秒后把目标 App 切到前台，Agent 第一次观察就能看到真实界面
adb shell am start -a android.settings.SETTINGS
```

| 参数 | 作用 |
|---|---|
| `task` | 直接作为任务文本启动 Agent，绕过输入法 |
| `delay` | 延迟 N 毫秒再开始，给外部切前台留时间 |
| `tokens` | 覆盖 token 预算，用于验证 TOKENS 维度耗尽 |
| `-f 0x20000000` | **必需**：SINGLE_TOP，否则 Activity 在栈顶时 Intent 被丢弃 |

配套诊断日志：

```bash
adb logcat -s DebugLaunch   # Intent 投递与消费
adb logcat -s LyraFocus     # elements/chars/handles/pkg，判断句柄是否该创建
```

实测数据（MuMu，Android 15 x86_64，系统设置页）：`elements=23 chars=322`，句柄阈值 150 时正常创建；`read` 返回 318–342 字符。