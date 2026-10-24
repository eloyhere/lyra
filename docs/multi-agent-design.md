# 多 Agent：以角色为核心的群聊（Lyra 落地设计）

> 现状：已存在 `ui/group/`（会话、角色、成员、消息、预算、回复策略）与 `service/groupchat/`（模型可调用的群聊操作）。
> 本文回答：群聊怎样从"多个模型轮流说话"变成"符合 AMP 的多 Agent"，以及代码怎么分层。

---

## 1. 关键判断：群聊不是多 Agent 的充分条件

现在每个角色已经有：
- 品牌 / 模型 / 思考等级（role）；
- 自己的发言记录（message.agentId）；
- 群级 token 预算（session.budget）。

缺的是 AMP 要求的三件事：

| 缺什么 | 后果 |
|---|---|
| **每 Agent 的 Manifest 切片** | 模型不知道自己被允许做什么、成本多少 |
| **每 Agent 的 Frame 与预算** | 无法判断"谁该在什么时候看什么"，只能全员看全文 |
| **发言仲裁（attention 的群体版）** | 谁说话由回复策略粗粒度决定，不是按显著性 |

所以多 Agent 的第一步不是并行调用，而是**给每个角色一份可审计的注意力和凭据**。

---

## 2. 目标模型

```
GroupSession（群）
├── members: List<GroupAgent>
│     ├── role        （品牌/模型/思考等级）
│     ├── manifest    ← 由 CapabilityCatalog 派生的切片（该角色被授权的服务子集）
│     ├── budget      ← 群预算的份额（tokens/ms/calls/money）
│     ├── frames      ← 每个角色的 Frame 链（看了什么、忽略什么）
│     └── warrant     ← 每个角色的 claim/hypothesis/unknown
├── turnPolicy        （谁说话：mentioned > speaker > salience > round-robin）
└── ledger            （群级事件：谁在何时基于什么说了什么）
```

### 2.1 发言仲裁（group attention）

```kotlin
selectSpeakers(
  candidates = members.map { it.salience(task, lastMessages) },
  budget     = groupBudget.remaining,
  policy     = session.replyPolicy,   // mentioned / all / moderator
): List<Speaker>
```

规则与单 Agent 一致：`salience` 高者先，成本高者后；`ignored` 要记录理由（"这一轮不相关"），可被下一轮重新唤起。

### 2.2 预算的两种切法

| 切法 | 适用 | 风险 |
|---|---|---|
| **均分**（群预算 / 成员数） | 成员能力相近 | 弱者拖住全场 |
| **按角色权重**（财务/协调者拿更多） | 有明确职责 | 需要人工维护权重 |

建议先均分、记录实际消耗，再按 `wasted_attention_ratio` 调整——不要一开始就手工调权。

### 2.3 群级审计

每次发言写三类事件（与单 Agent 同构）：

```json
{"type":"group.turn","agent":"a.1","role":"analyst","tokens":812,"cost_micros":97}
{"type":"group.frame","agent":"a.1","ignored":3,"reason":"不在本轮议题"}
{"type":"group.receipt","agent":"a.1","claim":"cl.12","tier":"T2","ok":true}
```

由此可算：**谁最费钱、谁最常被忽略、谁的发言被采纳率最高**。

---

## 3. 与单 Agent 的关系

```
SingleAgent（主路径）         GroupChat（协作路径）
  Frame / Receipt / Warrant    每人一份，群级汇总
  预算一份                     预算切片
  impasse 终止                 某成员 impasse → 换人或由协调者接管
```

两者共用：CapabilityCatalog、WarrantLedger、预算调度、压缩与剪枝、事件账本。**不要为群聊另写一套。**

---

## 4. 分层（配合 duplicate-audit 的 R1）

```
ui/group/GroupChatScreen        只渲染 + 调 orchestrator（不直接写 store）
ui/group/GroupChatSession       只读快照给 UI
core:group/GroupOrchestrator    回合调度 / 发言仲裁 / 预算 / 回复策略
service/groupchat/*             参数校验 + 调 orchestrator（模型入口）
core:attention/*                显著性、预算、剪枝（复用，不复制）
```

`GroupOrchestrator` 是唯一写状态的入口：UI 和模型都经它，消除双写。

---

## 5. 落地顺序

| 阶段 | 内容 | 出口 |
|---|---|---|
| **G1** | 抽 `GroupOrchestrator`，UI 与 Service 都改调它 | 群聊状态只有一条写入路径 |
| **G2** | 每成员 Manifest 切片 + 预算份额 | 成员越权调用被拒并记录 |
| **G3** | 发言仲裁（salience 排序）替换固定回复策略 | 群聊 token 下降、采纳率不降 |
| **G4** | 群级事件与指标（每人成本/采纳率） | 可回答"谁在浪费预算" |

---

## 6. 明确不做

- **不做无限循环的 Agent 互聊**：必须受 `maxDepth`（现为 2）与群预算双重约束，否则是烧钱机器；
- **不做共享上下文全文注入**：每个成员只看自己的 Frame，跨成员信息通过"发言"传递——这正是注意力层的意义；
- **不做 Agent 之间的私聊通道**：所有通信进群账本，可审计。
