# 手机服务与命令层（services）

> 把「模型/终端能对手机做的事」统一成稳定命令：`service.method`。
> 服务层只做能力封装；真正的安全闸门仍在 `AutomationEngine` / `PolicyGuard` / `ConsentManager`。

实现：`app/src/main/kotlin/com/eloykim/lyra/service/`

---

## 1. 分层

```
终端 / Agent
   │  CommandRegistry.run("touch.tap", {nodeId, obsId})
   ▼
ServiceHub ──┬── ScreenService       观察 / 等待屏幕变化
             ├── TaskService         系统任务记录（列表/数量/账本）
             ├── MissionService      应用内任务（创建/开始/完成/取消）
             ├── WindowService       窗口导航（home/back/recent/go）
             ├── ProviderService     查询服务与命令目录（只读）
             ├── StatusService       应用/设备/连接/权限/存储状态（只读）
             ├── UsageService        Token/耗时/花费（只读，供 UI 与命令）
             ├── NetworkService      网络状态/Wi-Fi/HTTP 请求
             ├── DatabaseService     只读 SQLite
             ├── ImpressionService   用户画像 / 画像事实（只读）
             ├── TouchService        点击 / 长按 / 滚动 / 按文字·ViewId
             ├── SwipeService        方向滑动（up/down/left/right）
             ├── TextService         读屏文本 / OCR / 输入 / 剪贴板
             ├── ScreenshotService   截图 / base64
             ├── ApplicationService  已安装应用 / 搜索 / 详情 / 启动 / 常用
             ├── ChatMessageService  聊天记录（只读）
             ├── ShortMessageService 短信发送/收件箱
             ├── VoiceService        TTS / 听写
             ├── NotificationService 通知发送/读取/交互式通知
             ├── SnackbarService     前台轻提示
             ├── DialogService       确认/选择/下拉/输入（阻塞等用户）
             ├── DeviceService       电量/音量/屏幕
             ├── IntentService       链接/拨号/邮件/地图/分享
             ├── AlarmService        闹钟/计时器
             ├── ContactsService     联系人（只读）
             └── CalendarService     日程（只读 + 编辑器）
```

- 每个服务实现 `PhoneService`，在 `init` 里注册 `ServiceMethod`；
- 每个服务自动拥有 `help` 命令（列出本服务命令与参数）；
- `ServiceHub.catalog()` / `CommandRegistry.catalogJson()` 是给 Agent 的目录；
- 终端与 Agent 共用同一个 `CommandRegistry`（`AppGraph.commands`）；
- `help` 等价于 `provider.list`（终端与命令 Registry 都支持）。

## 2. 查询服务本身

| 命令 | 说明 |
|---|---|
| `provider.list` | 服务列表（含 description、methodCount） |
| `provider.count` | 服务数、命令数、按风险分布 |
| `provider.commands` | 扁平命令列表；可按 service/query/risk 过滤 |
| `provider.get` / `provider.schema` | 单个命令的完整定义 / 参数 schema |
| `provider.search` / `provider.risk` | 按关键词 / 风险级别检索 |
| `provider.help` | Provider 自身命令；`command` 参数可查单条 |
| `<service>.help` | 任何服务的帮助，例如 `task.help`、`application.help`、`touch.help`、`dialog.help` |

## 3. 核心命令

| 命令 | 说明 |
|---|---|
| `screen.observe` / `screen.wait` | 抓屏 / 等 obsId 变化 |
| `task.list` / `task.status` | 系统任务记录 / 数量 |
| `task.ledger` / `task.verify` | AMP 账本 / 链校验 |
| `task.help` | 系统任务服务的帮助 |
| `mission.list` / `mission.get` / `mission.stats` | 应用内任务 |
| `mission.create` / `mission.update` / `mission.start` / `mission.complete` / `mission.cancel` / `mission.remove` | 管理应用内任务 |
| `window.home` / `window.back` / `window.recent` / `window.notifications` | 系统窗口导航 |
| `window.go` | 打开指定应用（包名或应用名） |
| `window.current` / `window.status` | 当前前台窗口 / 连接状态 |
| `application.recent` | 常用应用（来自本地使用记录） |
| `application.help` | 应用服务的帮助 |
| `status.overview` | 应用/设备/连接/屏幕/模型/账本总览 |
| `status.permissions` | 麦克风/通知/短信/联系人/日历/无障碍/通知使用权 |
| `status.connections` / `status.storage` / `status.version` | 连接、存储、版本 |
| `usage.report` | 完整 token 用度（按设置里的品牌/模型动态计价） |
| `usage.summary` / `usage.byAction` / `usage.byDay` / `usage.recent` | 用量汇总/明细 |
| `network.status` / `network.wifi` / `network.mobile` | 网络连接快照 |
| `network.enableWifi` / `network.disableWifi` | 尝试开关 Wi-Fi；受系统限制时打开面板 |
| `network.enableData` / `network.disableData` | 打开移动数据面板 |
| `network.get` / `network.post` / `network.request` | HTTP 请求（默认禁止内网） |
| `network.ping` / `network.openSettings` | 可达性探测 / 系统网络面板 |
| `database.tables` / `database.schema` / `database.count` | 表清单 / 结构 / 行数 |
| `database.sample` | 表的前 N 行 |
| `database.query` | 受限只读 SELECT/WITH；最多 200 行 |
| `impression.summary` / `impression.profile` | 用户画像摘要 / 完整 JSON |
| `impression.facts` / `impression.get` | `profile_facts` 列表 / 单条 |
| `impression.topApps` / `impression.topActions` / `impression.hours` | 画像统计 |
| `touch.tap` / `touch.tapById` / `touch.tapAt` / `touch.longPress` | 节点点击 / 坐标点击 / 长按 |
| `touch.tapViewId` | 按 View Id 点击（完整 `pkg:id/name` 或只给 `name`） |
| `touch.tapText` / `touch.tapByText` | 按文字找元素并点击 |
| `touch.scroll` | 滚动 |
| `swipe.up` / `swipe.down` / `swipe.left` / `swipe.right` | 按方向生成滑动手势 |
| `swipe.swipe` | 方向 + 距离/起点/时长 |
| `swipe.from` | 精确起终点滑动 |
| `text.readScreen` / `text.screenText` | 读屏 |
| `text.ocr` / `text.findText` | OCR / 文字定位（返回中心点） |
| `text.input` / `text.clear` | 输入框写入 / 清空 |
| `text.clipboardRead` / `text.clipboardWrite` | 剪贴板 |
| `screenshot.capture` / `screenshot.captureBase64` | 截图 / 缩放 base64 |
| `screenshot.latest` / `screenshot.list` | 缓存截图 |
| `application.list` / `application.search` | 已安装应用（含系统应用） |
| `application.info` / `application.launch` | 应用详情 / 启动 |
| `application.recent` | 常用应用（本地使用记录） |
| `chat_message.recent` / `chat_message.search` | 聊天记录检索（只读） |
| `chat_message.conversations` / `chat_message.conversation` / `chat_message.stats` | 会话与统计 |
| `short_message.send` / `short_message.draft` | 直接发短信 / 拉起编辑 |
| `short_message.inbox` / `short_message.thread` / `short_message.unreadCount` | 收件箱 |
| `voice.speak` / `voice.listen` / `voice.stop` | TTS / 听写 / 停止播报 |
| `notification.send` / `notification.cancel` | 发送/取消自己的通知 |
| `notification.prompt` | 交互式通知：按钮/输入框，**阻塞等待用户操作** |
| `notification.list` / `notification.history` | 读取活动/最近通知 |
| `notification.dismiss` / `notification.open` / `notification.dismissAll` | 撤回/打开通知 |
| `notification.openSettings` | 打开「通知使用权」设置 |
| `snackbar.show` | 前台轻提示（可等待按钮） |
| `dialog.confirm` / `dialog.choose` | 确认框 / 按钮多选 |
| `dialog.select` | **下拉框**，返回选中下标/文字 |
| `dialog.input` | **文本输入框**，返回用户输入 |
| `dialog.info` | 单按钮提示 |
| `device.status` / `device.battery` / `device.screen` | 设备状态 |
| `device.volume` | 音量读取/设置/静音 |
| `intent.openUrl` / `intent.dial` / `intent.email` / `intent.map` / `intent.share` | 标准 Intent |
| `intent.market` | 应用市场详情 |
| `alarm.setAlarm` / `alarm.setTimer` / `alarm.showAlarms` | 闹钟/计时器 |
| `contacts.search` / `contacts.get` / `contacts.lookup` / `contacts.count` | 联系人（只读） |
| `calendar.agenda` / `calendar.create` | 日程读取 / 创建编辑界面 |

### 首页命令的启动顺序

首页按「先列应用、再启动」执行，顺序固定：

1. `application.list`：列出本机全部可启动应用；
2. 模型从这份真实目录里选一个包名（不猜包名）；
3. `application.launch`：通过策略闸门打开目标应用；
4. 无障碍服务在目标应用上开始 observe / act。

没有合适应用时直接向用户提问，启动失败时结束本次运行——**不会退回 Lyra 自己的界面反复尝试**。

## 4. 用度计价（UsageService）

`usage.*` 不写账本，只读 receipt；费用按**设置里的当前品牌/模型**动态计算：

1. 从 `SettingsStore` 读 `brandId` / `model`；
2. 从模型目录取该模型的 `inputPrice` / `outputPrice`（USD / 1M tokens）；
3. 用 receipt 的 `tokens_in/out` 重算总费用、按动作、按天和最近回执；
4. 当前模型没有价格时，回退账本里已有的 `cost_micros`，并在结果里标 `costSource=stored_receipts`。

`UsageScreen` 也通过 `UsageService.snapshot()` 取同一份计算结果，并显示当前计价模型与单价；
UI 不再直接调用 `HistoryRepository.usageReport`。

## 5. Dialog / 通知：只能弹，不能替用户点

这是硬约束：

- `dialog.confirm/choose/select/input/info` **只弹出界面并挂起**，等用户点击/输入后返回；
- 模型不能直接「操作 dialog」：没有自动确认、自动选择、自动关闭的命令；
- `dialog.confirm` 不是安全授权通道；发消息/文件/账号仍必须走 `ConsentManager`；
- `notification.prompt` 同理：发一条带按钮/输入框的通知，服务挂起等待用户在通知里操作；
- `snackbar.show` 只显示轻提示，可等待用户点操作按钮；
- 所有 UI 交互都走命令，终端/Agent 不直接改 Compose 状态或调用 `LyraNotifications`/`SnackbarHostState`。

## 6. 只读边界

- `provider.*`、`status.*`、`usage.*`、`database.*`、`impression.*`、`network.status` **只读**；
- `database` 以 `SQLiteDatabase.OPEN_READONLY` 打开 `lyra_history.db`，SQL 只允许 `SELECT`/`WITH`，拒绝写关键字与多语句；
- `impression` 不写入 `profile_facts`，避免模型自我强化/篡改画像；
- `chat_message`、`contacts`、`calendar.agenda` 只读；
- 如果以后需要写数据库/画像，必须先通过 `dialog.confirm` 取得用户明确授权，再单独开写连接，不能复用只读服务。

## 7. 权限与系统设置

| 能力 | 需要 |
|---|---|
| 屏幕观察/点击/截图 | 无障碍服务（Lyra 操作服务） |
| 网络状态 | `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` |
| 尝试开关 Wi-Fi | `CHANGE_WIFI_STATE`；Android 10+ 通常只能打开面板 |
| HTTP 请求 | `INTERNET`；默认禁止内网/本机地址 |
| 通知读取/撤回 | 「通知使用权」（`LyraNotificationListener`） |
| 通知发送/交互式通知 | `POST_NOTIFICATIONS`（Android 13+） |
| 短信发送/读取 | `SEND_SMS` / `READ_SMS` 运行时权限 |
| 联系人 | `READ_CONTACTS` |
| 日历读取 | `READ_CALENDAR`；创建走系统编辑界面 |
| 语音听写 | `RECORD_AUDIO`；端侧模型未下载时回退系统识别 |
| 全部应用列表 | `QUERY_ALL_PACKAGES`（自用构建；上架需重新评估） |
| 闹钟/计时器 | `com.android.alarm.permission.SET_ALARM` |

权限未授予时命令返回 `permission_required`，不会静默降级。

## 8. 调用方式

Kotlin：

```kotlin
val hub = PhoneServices.create(context)          // 或 AppGraph.commands
val result = hub.call("touch", "tapAt", JSONObject().put("x", 540).put("y", 1200))
if (!result.ok) Log.w("Lyra", "${result.code}: ${result.message}")
```

Agent 命令：

```kotlin
val commands = AppGraph.commands
val result = commands.run("screen.observe")
val args = commands.parseArgs("maxDimension=1024 quality=80")
commands.run("screenshot.captureBase64", args)
```

终端：

```
help                           # 等价于 provider list
provider list                  # 查看服务
provider commands query=touch  # 查看 touch 的命令
cmd screen.observe             # 执行命令
cmd dialog.select title="选城市" options=["北京","上海"]
db tables
db query SELECT name FROM sqlite_master
impression summary
usage summary
network status
network get https://example.com
window go 微信
window home
mission list
swipe up
```

> `help` 等价于 `provider list`；每个服务还有 `<service>.help`（例如 `task.help`、`application.help`）。

## 9. 安全边界

1. **本层不绕过策略**：`touch` / `swipe` / `screenshot` / `application.launch` 最终仍走 `AutomationEngine` 的 `PolicyGuard`；支付、凭证、自身界面、系统设置仍被硬拒绝。
2. **dialog 不是授权**：`dialog.confirm` 只是产品交互。发消息/文件/账号的授权仍必须由 `ConsentManager` 弹出、由人确认。
3. **高敏数据**：`screenshot`、`notification.list`、`short_message.inbox`、`contacts.*`、`chat_message.*`、`database.query`、`impression.*` 标成 `SENSITIVE`。Agent 接入时应按风险级别决定是否先向用户说明/请求授权。
4. **截图/OCR 路径限制**：`text.ocr` 只允许读取 app 缓存 `shots/` 下的文件。
5. **交互式通知**：接收器 `exported=false`，只接受本应用显式 PendingIntent；用户的操作结果通过 `NotificationPromptStore` 唤醒挂起的命令。

## 10. 扩展一个命令

```kotlin
class MyService(private val context: Context) : BasePhoneService() {
    override val id = "my"
    override val title = "我的服务"
    override val description = "..."

    init {
        register(
            ServiceMethod(
                name = "ping",
                summary = "返回 pong",
                parameters = Schema.params(
                    "text" to Schema.string("回显文本"),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args ->
            ServiceResult.ok("pong", JSONObject().put("text", args.optStringOr("text")))
        }
    }
}
```

把实例加进 `PhoneServices.create`；`my.ping` 和 `my.help` 会自动出现在 `provider list` 与 Agent 目录里。

