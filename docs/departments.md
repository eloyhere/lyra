# 部门协作流程（Lyra 群聊）

> 目标：让 8 个部门在一个群里按同一套契约协作，而不是各自说话。
> 依据：AMP 团队认知（Role / Team manifest / Task board / Commitment / Handoff）+ 现有实现（v24–v33）。

## 1. 组织与职责

```
董事长（用户）
 └ 总经理（汇总、拆解、派活）
    ├ 产品部     需求澄清、结构化、验收标准
    ├ 工程部     技术方案、实现、可测性
    ├ 运营部     渠道、文案、上线计划
    ├ 职能部     流程、行政、跨部门协调
    ├ 法务部     合规、条款、风险
    ├ 人力部     入岗审批、角色分配、绩效记录
    ├ 财务部     预算、额度、成本
    └ 资源部     外部资源、算力、供应商
```

每个部门两个层级：**部长**（决策、对外承诺、提交审批）与**职员**（产出）。

## 2. 每个部门必须交付什么

| 部门 | 交付物 | 何时介入 |
|---|---|---|
| 产品 | 需求结构化（目标/范围/验收） | 任何需求进入执行前 |
| 工程 | 技术方案 + 工作量 + 风险 | 需求确认后 |
| 运营 | 上线/触达计划 | 方案定稿后 |
| 法务 | 合规结论（通过/有条件/否决） | 涉及隐私、条款、外部数据 |
| 人力 | 入岗审批、角色绑定 | 增员时 |
| 财务 | 预算与额度结论 | 增员、采购、长任务 |
| 资源 | 资源可用性结论 | 需要外部依赖 |
| 职能 | 流程检查、跨部门对齐 | 跨 3 个以上部门 |

## 3. 协作矩阵（谁向谁发起）

| 发起方 ↓ / 接收方 → | 产品 | 工程 | 运营 | 法务 | 人力 | 财务 | 资源 |
|---|---|---|---|---|---|---|---|
| 总经理 | 派需求 | 派实现 | 派上线 | 咨询 | 派增员 | 报预算 | 提资源 |
| 产品 | — | 交接规格 | 交接卖点 | 咨询 | — | — | — |
| 工程 | 澄清疑问 | — | 提上线条件 | 咨询 | — | — | 提算力 |
| 运营 | 反馈数据 | 提修复 | — | 咨询 | — | — | — |
| 法务 | 合规意见 | 整改要求 | 合规意见 | — | — | — | — |

规则：**跨部门只能通过「交接单 + 承诺」**，不能在群里随口一句就当派活。

## 4. 三条主流程

### 4.1 入岗（增员）

```
部长 staffing_request(positionKey, nickname)
  → 财务 staffing_approve(finance)     预算是否允许
  → 资源 staffing_approve(resource)    编制/资源是否允许
  → 人力 staffing_approve(hr)          三方齐 → 自动创建成员
  → 人力 assign_role(agentId, roleId)  绑定真实角色
  → manifest 自动刷新
```

任一方否决 → 申请停在 `proposed`，部长必须改需求再提；**不允许绕过**（除董事长直聘，见 §7）。

### 4.2 任务派发（执行）

```
总经理 task_create(title, owner, due, warrant_tier)
  → 部长 handoff_task(五要素) → 成员
  → 成员 task_synthesis（复述理解 + 第一步）→ doing
  → 完成 → commitment_discharge
```

没有复述就停在 `claimed`；超过 24h 无 discharge → `signal.commitment_orphan`。

### 4.3 需求交付（跨部门）

```
产品（结构化需求）
  → 法务（合规结论，可跳过但需记录原因）
  → 工程（方案 + 工作量）
  → 财务（成本确认）
  → 运营（上线计划）
  → 总经理汇总 → 董事长确认
```

每一跳都写一条 commitment（posted → grounded），形成可回溯的链。

## 5. 审批规则

| 事项 | 需要的审批 | 失败时 |
|---|---|---|
| 增员 | 财务 + 资源 + 人力 | 停在 proposed，记录否决方 |
| 预算调整 | 财务 | 拒绝并说明依据 |
| 合规例外 | 法务 + 总经理 | 一律否决 |
| 外部资源采购 | 资源 + 财务 | 停在 proposed |
| 上线 | 工程 + 运营 + 产品 | 延期并记录阻塞项 |

## 6. 交接契约（I-PASS）

每次跨部门交接必须包含：**背景 / 现状 / 判断 / 请求 / 验收**。
接收方必须复述「我理解…我的第一步…」才能开工。
缺任一项 → `signal.handoff_without_synthesis`。

## 7. 初始化：董事长直聘

冷启动时没有审批人，允许董事长（用户本人，`actorAgentId=null`）直接创建成员：

```
hire_member(positionKey, nickname, roleId)   // 仅用户可用
```

直聘会写 `signal.direct_hire`，留痕但不阻塞。

## 8. 升级与超时

| 情形 | 处理 |
|---|---|
| 部长 5 轮未响应 | 总经理转派，写 `signal.escalated` |
| 承诺超期 | 财务/人力复核，可强制释放名额 |
| 审批 24h 未齐 | 提醒提议人，不自动通过 |
| 成员超额 | 跳过发言 + `signal.budget_exhausted` → 财务处理 |

## 9. 信号与指标

已有：`intake_skipped`、`handoff_without_synthesis`、`commitment_orphan`、`duplicate_work`、`stale_directory`、`false_consensus`、`budget_exhausted`。

按部门聚合后回答：

- 谁的交接最常缺复述（配合问题）；
- 哪个部门审批最慢（瓶颈）；
- 哪个部门任务返工率最高。

## 10. 与实现的对应

| 流程 | 服务 |
|---|---|
| 入岗 | `staffing_request` / `staffing_approve` |
| 任务 | `task_create` / `task_claim` / `task_move` |
| 承诺 | `commitment_post` / `ground` / `discharge` |
| 交接 | `handoff_task` / `task_synthesis` |
| 未知 | `unknown_raise` / `unknown_resolve` |
| 预算 | `budget` / `set_member_quota` / `metrics` |
| 团队 | `manifest` |
