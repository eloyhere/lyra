# 重复代码审核（以 service/ 为准）

> 规则：凡 `service/` 已实现的能力，其它目录里的同类实现一律让位。
> 审核时间：2026-10-25；范围：`app/src/main/kotlin/**`（87 个文件）+ `core/**`。

## 1. 结论摘要

| 结论 | 数量 |
|---|---|
| 真实重复（需改） | 3 处 |
| 分层合理、无需改动 | 2 处 |
| 服务目录本身需要重构（而非被替代） | 1 处 |

## 2. 真实重复

### R1 群聊状态操作：UI 与 GroupChatService 双写

| 位置 | 行为 |
|---|---|
| `service/groupchat/GroupChatService.kt` | 注册 `sessions / new_session / rename_session / delete_session / clear_history / budget / set_budget / set_reply_policy / agents / roles / create_role` |
| `ui/group/GroupChatScreen.kt` | 直接调用 `AppGraph.history.groupChat.addAgent/removeAgent/updateAgent/createRole/updateRole/setSessionScenario/updateScenario` |
| `ui/group/GroupChatSession.kt` | 直接读写 `groupChat` store，并自行维护预算与回复策略 |

**判定**：同一组状态有两条写入路径。服务侧是模型可调用的权威接口，UI 应改为经服务（或服务与 UI 共用的 façade）写入。

**处置**：
1. 保留 `GroupChatService` 的全部方法签名与语义；
2. UI 的写操作改为调用同一层 façade（见 R6 的 `GroupOrchestrator`）；
3. UI 只保留读取与渲染。

### R2 聊天上下文装配：ChatStore 与 ChatMessageService 各装配一次

| 位置 | 行为 |
|---|---|
| `ui/chat/ChatStore.kt` | 组装 `LlmMessage`、调用 `ChatCondenser`、拼 system prompt |
| `service/chatmessage/ChatMessageService.kt` | `conversation` 返回消息，也调用 `ChatCondenser`、返回 summary/digest |

**判定**：压缩规则（阈值 40 条 / 6000 字、keepRecent 12）在两处各写一遍，已经出现"改一处忘另一处"的风险。

**处置**：保留 `ChatMessageService` 作为唯一装配入口；`ChatStore` 改为向服务请求"已压缩的上下文"，不再自行 condense。

### R3 通知渠道与投递

| 位置 | 行为 |
|---|---|
| `notify/LyraNotifications.kt` | 定义 `NoticeLevel`、建渠道、`post()` |
| `service/notification/NotificationService.kt` | 使用 `NoticeLevel.COMMITMENT.channelId` 组装并 `notify()` |

**判定**：不是重复，是**依赖倒置**——`service/` 依赖 `notify/`。按"以 service 为准"，应把 `NoticeLevel` 与渠道定义下沉到 service 层，`notify/` 只做薄封装或删除。

**处置**：把 `NoticeLevel` 移到 `service/notification/`，`notify/` 保留一个委托入口（调用点少，迁移成本低）。

## 3. 分层合理、无需改动

### L1 ChatMessageService ↔ ChatMessageStore

服务是只读投影，store 是持久化；服务依赖 store 正确。

### L2 ServiceHub ↔ 40 个 domain service

`ServiceHub` 只做路由与目录，domain service 各自封装系统 API；无重复。

## 4. 服务目录自身的重构点

`GroupChatService.kt` 17.5KB、`ChatMessageService.kt` 12.6KB，都在"服务方法 + 业务编排"之间混合。多 Agent 落地前应把编排抽出去：

```
service/groupchat/GroupChatService.kt   → 只做参数校验 + 调用编排
core:group/GroupOrchestrator.kt         → 回合调度、预算、回复策略、角色解析（新模块）
ui/group/GroupChatScreen.kt             → 只渲染 + 调服务
```

## 5. 处置顺序

| 顺序 | 动作 | 风险 |
|---|---|---|
| 1 | `NoticeLevel` 下沉到 service 层 | 低（编译期可验） |
| 2 | 抽 `GroupOrchestrator`，UI 改调它 | 中（涉及群聊主流程） |
| 3 | `ChatStore` 复用 `ChatMessageService` 的装配结果 | 中（涉及聊天上下文） |

## 6. 不做的事

- 不删除 `ui/group/GroupChatSession.kt` 的会话快照读取逻辑（渲染需要它）；
- 不把 40 个 domain service 合并成一个巨型类（目录税另有解，见 next-architecture 的 CapabilityCatalog）。
