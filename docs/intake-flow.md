# 需求澄清与派单流程（基于 AMP + 实践证据）

> 目标：董事长一句话之后，群聊不能直接开工，而要经过「澄清 → 承诺 → 拆解 → 交接」四道闸。
> 依据：AMP `research/team-cognition.md` §3–4、§9；外部实践 I-PASS/SBAR、多 Agent 任务板。

## 1. 问题

现在的流程是「总经理派产品部长问用户 → 再建任务」，但缺三样东西，导致它经常退化成闲聊：

1. **没有澄清完成的判据**——问到哪算问完？
2. **没有承诺**——部长说"我来拆"，谁答应了？谁验收？
3. **没有交接复述**——任务从部长到职员，职员没确认理解就动手。

AMP 的团队认知研究把这三样分别叫 *grounding*、*joint commitment*、*handoff synthesis*，其中 handoff 的工业标准是 I-PASS/SBAR（接收方必须复述）。

## 2. 目标流程（五段）

```
① 触发      董事长一句话
② 接单      总经理 → task(intake, needs_info, owner=产品部长)
③ 澄清      产品部长提问（≤3 个/轮）→ 董事长回答
              每问一条记 unknown(blocking)；每个回答记 claim(T1)
④ 承诺      产品部长交规格（posted）→ 总经理确认（grounded）
              → 拆任务（task_create，带 depends_on / warrant tier / due）
⑤ 交接      部长 → 职员：交接必须含
              【背景 / 现状 / 判断 / 请求 / 验收标准】
              职员回复「我理解…我的第一步…」才算 grounded
```

### 2.1 澄清清单（按需裁剪，不逐条问）

- **目标**：做成什么样算成功（验收标准）；
- **范围**：明确不做哪些；
- **约束**：时间、渠道、不可用的资源；
- **优先级**：只能做一半时先做哪半。

每轮最多 3 个问题——AMP 的注意力预算要求提问本身也计价。

### 2.2 状态机

```
proposed → needs_info → ready → claimed → doing → done
                 ↑            ↓         ↓
                 └── blocked ←┘    dropped
```

- `needs_info`：等董事长回答，任何成员不得越过它开工；
- `ready`：规格已 grounded，可认领；
- `blocked`：依赖未满足或等待外部条件，必须写原因。

### 2.3 承诺记录（新表 `group_commitments`）

```
id, session_id, from_agent, to_agent, proposition, satisfaction,
state(posted/grounded/discharged/cancelled/breached), due_at
```

规则来自 AMP：
- `posted` 只是"说了"；`grounded` 必须对方确认；
- 完成必须有 `discharged`，否则记 **commitment orphan**；
- 单方取消记 `breached`，不能静默消失。

### 2.4 交接模板（I-PASS 的五要素）

| 字段 | 内容 |
|---|---|
| 背景 | 这件事从哪来、为什么做 |
| 现状 | 已经做了什么、卡在哪 |
| 判断 | 我建议怎么做（带凭据等级） |
| 请求 | 你要做的具体动作 |
| 验收 | 什么算完成 |

职员必须复述「我理解…我的第一步…」，否则任务停在 `claimed`，不进入 `doing`。

## 3. 失败信号（写进事件账本）

| 信号 | 判据 |
|---|---|
| `intake_skipped` | 任务从 `proposed` 直接到 `doing`，没有 `needs_info` 记录 |
| `commitment_orphan` | 任务 done 但无 `discharged` 承诺 |
| `handoff_without_synthesis` | 交接消息后没有接收方复述 |
| `false_consensus` | 高风险决定零反对 |
| `duplicate_work` | 两个 doing 任务标题相似度 > 0.8 |

## 4. 已实现 / 待实现

| 项 | 状态 |
|---|---|
| 任务板 `group_tasks` + 四服务 | ✅ |
| 状态机新增 `needs_info` / `ready` | ✅ |
| 空任务板自动生成 intake 任务 | ✅ |
| 提示词要求"产品部长先澄清、后拆任务" | ✅（仅在 system prompt 内） |
| 澄清清单模板与 `unknown(blocking)` 记录 | ✅ `group_unknowns` + 三方法 |
| `group_commitments` 表与 grounded 流程 | ✅ posted/grounded/discharged |
| 交接五要素 + 接收方复述强制 | ✅ `handoff_task` + `task_synthesis` |
| 失败信号计算 | ✅ 三类信号写入账本 |

## 5. 参考

- AMP `docs/research/team-cognition.md` §3 joint commitment、§4 grounding、§9 design implications
- [AHRQ TeamSTEPPS: I-PASS](https://www-innovations-ahrq-gov.translate.goog/teamstepps-program/curriculum/communication/tools/ipass.html)
- [Stanford 住院医师交接协议](https://med.stanford.edu/scalpel/residency-handbook/handoffs-transfer-protocols)
- [多 Agent 任务板实践（Hermes Kanban）](https://agentpedia.codes/blog/hermes-kanban-multi-agent-board-guide)
