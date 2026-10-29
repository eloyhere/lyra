# 招聘流程与任务流程（研究与落地）

> 问题：部长们在群里一直讨论，却不给自己部门招人、也不推进任务。
> 结论：这不是提示词写得不够狠，而是**通道**问题——成员只有"说"的通道，没有"做"的通道；
> 再加上"每轮全员接力 + 三方会签"，讨论成本按人数线性增长，决定成本却无人承担。

## 1. 诊断：三个结构性原因

| # | 现象 | 根因 | 证据 |
|---|---|---|---|
| 1 | 部长只表态，从不招人 | 群聊接力调用模型时 `tools = emptyList()`，回复是纯文本；`staffing_request` 服务在模型侧根本不可达 | `GroupChatSession.send()` 只发 system + history，无工具 |
| 2 | 每轮都在重复讨论 | 固定把总经理 + 8 个部长全部拉进接力，每轮 9 次模型调用，谁都能说、谁都不用负责 | `Positions.relay` 被逐个人发言 |
| 3 | 三方会签永远凑不齐 | 财务/资源/人力三次审批都是"再调一次模型"，任一方不表态就停在 `proposed` | `group_staffing` 的 finance/resource/hr 三列 |

第 2、3 条叠加的效果最糟：人越多，讨论越长；审批链越长，越不可能走完。

## 2. 外部资料给出的三条修正

1. **静态角色会制造瓶颈，应该按需分配**。
   CODA 框架把"固定角色排队"改成"按任务动态分配"，
   与本项目"部长固定接力"的结构性问题一致：<https://zenodo.org/records/22891928>
2. **多 Agent 的失败多数是协作失败，不是模型能力失败**。
   组织式多 Agent（把异构 Agent 当公司来编排）强调岗位、职级与招聘要服务于任务，
   而不是先摆一张组织架构图：<https://huggingface.co/papers/2604.22446>
3. **审批要按置信度/风险升级，而不是逐级会签**。
   人在环审批的工业做法是"阈值内自动通过，超阈值才找人"，
   对应内控里的 management by exception：<https://learn.microsoft.com/nb-no/training/modules/aaai-design-human-in-loop-approval-workflows/2-design-confidence-threshold-escalation>
   对话终止也必须显式定义（最大轮次、终止判据），否则 Agent 群聊天然不会停：
   <https://github.com/ag2ai/ag2/blob/c31a08e42ae4ef5ef7171bff22cd56049f668add/website/docs/user-guide/advanced-concepts/ending-a-chat.mdx>

映射到 Lyra：

| 外部做法 | Lyra 落地 |
|---|---|
| 按需分配，不排队 | 每轮只叫相关部门的部长（≤4 人），不再全员接力 |
| 岗位服务于任务 | 招聘必须带 `reason`（哪件事卡住）与 `expected`（第一周交付） |
| 阈值内自动通过 | 编制/预算内的增员由 `HiringPolicy` 直接批准并入职，0 次审批调用 |
| 超阈值升级 | 超编、超预算、敏感席位 → 一条待批申请，董事长点一次 |
| 显式终止 | 一轮没有任何动作时明说"无状态变化"，不再无限接话 |

## 3. 招聘流程（改后）

```
① 触发        任务 blocked（原因含"缺人"）或部长发现能力缺口
② 申请        request_hire(position, reason, expected)      ← 1 次调用
③ 判定        HiringPolicy.evaluate(facts)                   ← 纯函数，0 token
                 ├─ Reject        理由不成立 / 已有人在岗 / 未决申请 ≥2
                 ├─ AutoApprove   编制与预算内
                 └─ NeedChairman  超编 / 超预算 / 敏感席位
④ 执行        人力（系统）建成员 + 绑角色 + 刷新 manifest
⑤ 留痕        group.hiring.requested / .completed / .rejected
```

### 3.1 为什么去掉三方会签

| | 三方会签（旧） | 策略门（新） |
|---|---|---|
| 模型调用 | 1 次申请 + 3 次审批 | 1 次申请，制度内 0 次审批 |
| 失败模式 | 任一方不表态 → 永久 `proposed` | 判定是纯函数，不会沉默 |
| 责任 | 三方都点头，等于没人负责 | 人力执行、董事长兜底 |
| 财务/法务 | 各占一次发言 | 变成**约束**（预算余量）与**风险位**（敏感席位） |

财务与法务并没有被删掉，而是换了位置：

- 财务 = `HiringFacts.remainingTokens`，低于 `minRemainingTokens` 就升级；
- 法务/合规 = `HiringPolicy.sensitivePositions`（能改预算、角色、合规结论的席位），
  永远升级给董事长；
- 人力 = **执行者**（建成员、绑角色、刷新清单），不再只是第三个"同意"。

### 3.2 判定规则（`core/attention/HiringPolicy.kt`）

| 顺序 | 条件 | 结果 |
|---|---|---|
| 1 | 理由为空 | Reject |
| 2 | 该职位已有人在岗 | Reject |
| 3 | 未决申请 ≥ 2 | Reject |
| 4 | 敏感席位（职能/法务/人力/财务/资源部长） | NeedChairman |
| 5 | 成员数 ≥ 12 | NeedChairman |
| 6 | 部门人数 ≥ 3 | NeedChairman |
| 7 | 预算余量 < 120k tokens | NeedChairman |
| 8 | 其余 | AutoApprove |

阈值都在 `HiringLimits` 里，改一行就能调。

### 3.3 状态

```
proposed ──批准──> created        （入职，写 agent_id）
    └────驳回──> rejected
```

兼容：自动批准时把旧的 `finance_ok / resource_ok / hr_ok` 一次置 1，
仍按三列读数据的路径不会认为"没批"。

## 4. 任务流程（改后）

```
需求 ──create_task(title, owner, acceptance)──> proposed
                                                 │
                              claim（WIP=1）     ▼
proposed ──> ready ──> claimed ──> doing ──> review ──> done
                ▲                     │          │
                └──── blocked ◄───────┘          └── 驳回 ──> doing
```

规则（`core/attention/TaskFlow.kt`）：

- `doing → review → done`：完成必须有人验收，**自己不能验自己**；
- 每人同时只能有一个 `doing`（WIP=1），领新任务前先收尾；
- `blocked` 必须写原因；原因里出现"缺人/没人/招聘/增员"时，
  这条任务就是 `request_hire` 的合法依据；
- 同名未完成任务不再重复创建（去重）。

## 5. 群聊怎么真的"动起来"

1. **行动通道**：回复最后一行 `<<ACTION {...}>>`，宿主解析、鉴权、执行，
   把结果作为系统消息写回群里（`group/GroupActions.kt`）。
   动作：`request_hire` / `create_task` / `move_task` / `claim_task`。
2. **按需召集**：`selectSpeakers()` 只叫总经理 + 相关部长（关键词、任务负责人部门、待批申请部门），
   最多 4 人；没有线索时退回"产品 + 工程"。
3. **显式终止**：一轮结束后如果没有任何动作，系统补一句"本轮 N 人发言，没有任务、增员或状态变化"，
   下一轮不再自动重开话题。
4. **状态可见**：每个发言者的 system prompt 里有成员、任务板、待批增员三块摘要，
   不需要靠回忆上下文判断"现在卡在哪"。

## 6. 失败信号（写账本）

| 信号 | 判据 |
|---|---|
| `group.hiring.rejected` | 申请被策略拒绝（无理由/已有人/积压） |
| `group.hiring.rejected.by.chairman` | 董事长驳回待批申请 |
| `group.turn.completed(actions=0)` | 一轮讨论没有任何状态变化 |
| 任务 `blocked` 无原因 | 建议补一条校验（未实现） |

## 7. 实现映射

| 能力 | 文件 |
|---|---|
| 判定规则 + 任务状态机 | `core/attention/HiringPolicy.kt`、`TaskFlow`（同文件） |
| 申请表与读写 | `core/data/GroupChatStore.kt`（`StaffingRecord` / `createStaffing` / `decideStaffing`） |
| 招聘台 | `app/.../group/HiringDesk.kt` |
| 行动协议 | `app/.../group/GroupActions.kt` |
| 按需召集 + 动作回执 | `app/.../ui/group/GroupChatSession.kt` |
| 服务接口 | `groupchat.staffing_request` / `staffing_approve` / `hiring_list` |
| 待批 UI | 群聊页顶部 `HiringStrip`（批准 / 驳回） |

## 8. 还没做的

- 招聘冷却：同一职位被拒后 24h 内不再受理（防止模型反复申请）。
- 任务与增员的强关联：`request_hire` 目前只带 `reason` 文本，没存 `taskId`。
- 预算按部门分配：现在只有群级预算，部门额度靠 `maxPerDepartment` 间接限制。
- 绩效回流：入职后的产出没有回写到席位评价。

## 9. 参考

- CODA：动态分配替代静态角色瓶颈 — <https://zenodo.org/records/22891928>
- 把异构 Agent 组织成公司（岗位/招聘/任务） — <https://huggingface.co/papers/2604.22446>
- 人在环审批的置信度阈值升级 — <https://learn.microsoft.com/nb-no/training/modules/aaai-design-human-in-loop-approval-workflows/2-design-confidence-threshold-escalation>
- 对话终止判据（AG2） — <https://github.com/ag2ai/ag2/blob/c31a08e42ae4ef5ef7171bff22cd56049f668add/website/docs/user-guide/advanced-concepts/ending-a-chat.mdx>
- 本项目既有流程：`docs/intake-flow.md`、`docs/departments.md`
