# AMP 0.4.1 接入与一致性

> 参考实现：`R:\project\attention-manifest-protocol`（包版本 0.4.1，wire schema 仍为 `0.1`）
> Kotlin 端口：`core:amp-protocol`（纯协议层，无 Android UI 依赖；219 个源文件 / 约 5.3 万行）
> 外部校验：`tools/amp-conformance/*.mjs`（Node 24 直接读取参考仓库的 schema 与 canonical 实现）

Lyra 的目标不是「字段看起来像 AMP」，而是**导出的 JSON 能被参考实现重新计算 digest、验证签名、通过 schema**。为此协议层与设备运行时是分开的两层：

```
core:amp-protocol   参考实现的 Kotlin 端口（Frame/Receipt/Ledger/Manifest/Attention/
                    Warrant/Governance/Runtime/Context/MCP/A2A/...）
core:agent          Lyra 的 Agent 循环；AmpFrameAdapter 把焦点草稿变成协议 Frame
core:data           只追加的 SQLite 落库；payload 是 canonical JSON
app                 真机 UI、授权闸门、调试探针
```

## 1. 版本关系

| 层 | 版本 |
|---|---|
| 参考包 | `0.4.1` |
| wire schema | `0.1`（`docs/schemas/*.schema.json`） |
| Kotlin 协议对象 | `amp: "0.1"`，`AmpWire.KERNEL_VERSION = "0.4.1"` |

wire 版本没有跟着包版本升，是因为参考仓库的 schema `$id` 仍是 `0.1`；Lyra 不伪造 `0.4.1` 的 wire 对象。

## 2. 已对齐的硬约束

| 约束 | 实现点 |
|---|---|
| canonical JSON | `semantic/CanonicalJson.kt`：递归排序键、JS `JSON.stringify` 数字/字符串格式、无空白 |
| digest | `sha256(canonical(body))`；Frame/Manifest/链条目/检查点全部走这一条路径 |
| Manifest 签名 | 对「去掉 `signatures` 的 canonical body（含 `digest`）」签名；base64url；`verifyManifest` 先重算 digest |
| Frame 边界 | 9 slot 上限、ignored 上限 50 + `deferred`、引用闭包、预算一致性、id 语法 |
| 只追加 | `Ledger` 只有 append；`FrameStore` 没有 UPDATE；`markImpasse` 改为事件，不再回写旧帧 |
| Receipt/Event | `receipt.schema.json` 的 `oneOf`；`handle` 可选；`usage` 五维 |
| 账本快照 | `{seq,kind,payload,prev}` 哈希链；`verifySnapshot` 可检出改、删、重排 |
| 检查点 | 域分隔 `amp:ledger-checkpoint:0.1`；`prev` 首条省略；Ed25519 签名 |
| 治理/运行时 | Matrix/Perspectives/Legal/Budget/Control/AttentionManifest/Credential/Workflow/Audit/Transport/Switching/ToolPolicy 等端口 |
| MCP/workspace | JSON-RPC 信封、stateless/legacy 协商、七工具、1 MiB/64 MiB 限制、通知不执行工具 |

## 3. 外部可验证

先跑 Kotlin 测试导出真实对象：

```bash
./gradlew :app:testDebugUnitTest
node tools/amp-conformance/validate-wire.mjs
node tools/amp-conformance/compare-canonical.mjs
node tools/amp-conformance/compare-numbers.mjs
```

输出：

- `app/build/amp-wire/{frame,receipt,manifest,ledger-snapshot}.json`
- `manifest-public-key.b64`（SPKI DER，base64）
- `amp-canonical-kotlin.json` / `amp-numbers-kotlin.json`

`validate-wire.mjs` 用参考仓库的 schema 校验、重算 Frame/Manifest digest、用导出的公钥验证 Ed25519 签名，并重算 ledger 快照的每条链哈希。当前结果：`wire conformance: 4/4`，`canonical 18/18`，`number formatting 31/31`。

## 4. Lyra 运行时绑定

`core:agent/AmpFrameAdapter.kt` 是唯一把「注意力选择器的 FrameDraft」变成协议 `Frame` 的地方：

```
AttentionSelector.select(...) -> FrameDraft
  -> frameFrom(draft, refTable)   // 算 digest、拒绝悬空引用
  -> FrameStore.append(runId, Frame) // payload = canonical JSON
```

- goal/tool 引用在 `RefTable` 中登记；UI 节点使用 `entity`（opaque）引用。
- slot 的 `why`、`cost`、`salience` 来自选择器；ignored 的 `reason`/`revisit_if` 原样保留。
- 模型只看到焦点集与忽略摘要，不再看完整树。
- `AgentSession` 的 `EventSink` 现在写入 `amp_protocol_events`：每条是完整 canonical `AmpEvent`，`hash = digestOf({seq, kind:"event", payload:event_json, prev})`，与参考实现 `ledger/persistence.ts` 同构。旧 `LedgerStore` 的 `amp_events` 表保留为历史投影，不再写入。
- SQLite v36 是一次开发期清空迁移：旧 `frames`/`amp_events` 的自定义 JSON 与哈希链不再兼容，`LyraDatabase.onUpgrade` 会删除应用自己的表并重建。**这会导致聊天、任务、画像等数据一起清空**；发布版应改为增量迁移，而不是沿用这条开发迁移。

调试探针（仅 debug 构建）：

```bash
adb shell am start -n com.eloykim.lyra/.MainActivity \
  -e task "focus_probe" -e focus 1 -e self 1
adb shell "run-as com.eloykim.lyra sqlite3 databases/lyra_history.db \
  'SELECT digest,substr(payload,1,240) FROM frames ORDER BY id DESC LIMIT 1'"

# 协议事件账本自检：追加一条事件，再用 Node 重算 digest/hash
adb shell am start -n com.eloykim.lyra/.MainActivity -e command amp.appendTest
adb shell "run-as com.eloykim.lyra sqlite3 databases/lyra_history.db \
  'SELECT seq,digest,hash FROM amp_protocol_events ORDER BY seq DESC LIMIT 1'"
```

`-e focus 1` 走 `FocusProbeBackend`（只生成一帧、不调模型）；`-e self 1` 仅 debug 下允许观察 Lyra 自身界面。普通任务仍走 `LlmAgentBackend`，`self` 默认关闭。`amp.appendTest` 只在 debug 的 `CommandDebug` 分支里存在。

## 5. 覆盖与已知缺口

已移植：`attention/authz/bayes/boundary/concept/concurrency/context/cooperation/crypto/database/delegate/diagnosis/error/evaluation/evidence/execution/frame/goal/governance/interaction/ipc/json/kernel/ledger/lock/manifest/mission/module/policy/progress/protocol/receipt/registry/runtime/scenario/schema/semantic/storage/store/structure/thread/types/warrant/window`。

明确缺口 / 偏差：

| 项 | 状态 |
|---|---|
| `src/database/**`（59 文件） | SQL/entity/repository/service/store 层已移植；Lyra 设备侧仍由 `core:data` 的 Android SQLite 适配器承接 |
| `database` 测试 | 端口已编译；JUnit 覆盖仍待补 |
| `registry/registry.ts` | 已补 `Registry.kt` / `RegistryStore.kt` / `RegistryTypes.kt`，含模块漂移校验 |
| `store/database` 实体 | 窄接口 + 内存适配器；真实 SQLite 绑定在宿主侧 |
| Ed25519 | JCA；Android < API 33 无 provider 时签名失败关闭，不伪造 |
| HTTP server | `ServerSocket` + Content-Length；不支持 chunked / TLS |
| 治理对象 JSON | 参考仓库没有 schema 的部分按 TS 接口 camelCase 序列化；不伪造 schema |
| `policy` 未知 effect | 在 `fromJson` 直接拒绝（比参考实现更早失败关闭） |

这些偏差都要求「宁可拒绝，不可静默降级」；新增能力时先补 schema 与外部校验，再接进 Agent 循环。

## 6. 自检

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
node tools/amp-conformance/validate-wire.mjs
```

当前：协议层 1073 条 JUnit、Agent 层 31 条、注意力层 80 条、wire 4/4、canonical 18/18、数字格式 31/31；MuMu `emulator-5554`（Android 15 / API 35）安装运行，SQLite 中的 Frame payload 与协议事件 `event_json` 均已用 Node 重算 schema/digest/hash 并通过。
