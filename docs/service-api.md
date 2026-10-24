# 自动化服务契约（v1）

> Lyra 把「看屏幕 + 操作屏幕」封装成一个绑定服务。模型协议、Agent 循环、UI 都通过它访问设备能力，
> 不直接调用 `AccessibilityService`。
>
> 实现：`core:accessibility` · 接口：`com.eloykim.lyra.automation.IAutomation`（AIDL）

---

## 1. 边界

```
UI / Agent 协议（另一会话）
      │  bindService
      ▼
AutomationService  ──AIDL──▶  AutomationEngine
                                  │ 主线程封送
                                  ▼
                        LyraAccessibilityService（AccessibilityNodeInfo / performAction / dispatchGesture）
```

- 服务**不自己抓屏**，而是驱动无障碍服务持有的同一个引擎 —— 保证「UI 看到的」和「协议操作的」是同一份快照。
- 所有方法同步返回 JSON 字符串，调用方（Kotlin）在 IO 线程调用即可。
- 当前 `exported=false`，仅应用内绑定；跨进程/跨应用开放只需改 Manifest 并加 signature 权限（见 §7）。

---

## 2. 接口

```aidl
interface IAutomation {
    boolean isReady();
    String observe();
    String tap(int nodeId, long obsId);
    String setText(int nodeId, long obsId, String text);
    String scroll(int direction, long obsId);
    String globalAction(String action);
    String launch(String packageName);
}
```

| 方法 | 说明 | 耗时 |
|---|---|---|
| `isReady()` | 无障碍服务是否已连接 | < 1ms |
| `observe()` | 抓取当前屏幕，返回压缩观察 | 20~40ms |
| `tap(nodeId, obsId)` | 点击；不可点击时退化为坐标手势 | 动作 + 260ms settle + 抓取 |
| `setText(nodeId, obsId, text)` | `ACTION_SET_TEXT` 写入输入框 | 同上 |
| `scroll(direction, obsId)` | 0 下 / 1 上 / 2 左 / 3 右 | 同上 |
| `globalAction(action)` | `back` / `home` / `recents` / `notifications` | ~50ms |
| `launch(packageName)` | 启动已安装应用 | ~100ms |

**动作方法的返回值里已经包含动作之后的新观察**，调用方不需要再 `observe()` 一次。

---

## 3. 观察（observe）

```json
{
  "ok": true,
  "obsId": 42,
  "observation": {
    "obsId": 42,
    "package": "com.android.settings",
    "capturedAt": 1761291731000,
    "truncated": false,
    "counts": { "nodes": 75, "interactive": 14, "sent": 9 },
    "elements": [
      {
        "id": 8,
        "role": "linearlayout",
        "text": "设置",
        "bounds": [0, 107, 1080, 437],
        "clickable": true
      },
      {
        "id": 11,
        "role": "text",
        "text": "Settings",
        "bounds": [0, 261, 1080, 196]
      }
    ]
  }
}
```

字段约定：

| 字段 | 说明 |
|---|---|
| `obsId` | 观察版本号，单调递增；动作时回传 |
| `package` | 当前前台应用包名 |
| `counts.nodes` | 完整节点数（最多 2500，超出 `truncated=true`） |
| `counts.sent` | 压缩后发给模型/调用方的元素数 |
| `elements[].id` | **只在本次观察内有效**的编号（BFS 顺序） |
| `elements[].role` | 控件类型小写：`button` / `edit` / `text` / `tab` / `recycler` … |
| `elements[].bounds` | `[left, top, right, bottom]`，屏幕像素 |
| `clickable/editable/scrollable` | 只在为 `true` 时出现 |

压缩规则（为什么不是整棵树）：只保留「可见 &&（有文字 || 可点击 || 可输入 || 可滚动）」的节点。
实测设置页 75 个节点 → 9 条元素，模型输入量下降约 90%。

---

## 4. 动作与错误

成功：

```json
{ "ok": true, "message": "clicked", "obsId": 43, "observation": { ... } }
```

失败：

```json
{
  "ok": false,
  "code": "stale_observation",
  "message": "屏幕已变化（当前 obsId=43），请基于新观察重试",
  "obsId": 43,
  "observation": { ... }
}
```

失败也会尽量附带最新观察，让调用方（或模型）一轮内自我纠正。

| code | 含义 | 是否可重试 |
|---|---|---|
| `service_unavailable` | 无障碍服务未连接 | 重新开启无障碍 |
| `no_window` | 当前没有可访问窗口（锁屏/系统弹窗） | 稍后 |
| `no_observation` | 还没观察过就发动作 | 先 `observe()` |
| `stale_observation` | `obsId` 或元素指纹对不上 | 用返回的新观察重试 |
| `invalid_id` | 编号越界 | 用新观察的编号 |
| `not_editable` | 目标不是输入框 | 换元素 |
| `no_scrollable` | 没有可滚动区域 | — |
| `app_not_found` | 应用未安装 | — |
| `action_failed` | `performAction` 返回 false | 可重试一次 |
| `blocked` | 命中硬边界（支付 / 凭证 / 自身 / 系统设置），**永远不可解锁** | 放弃该路径，交给用户 |
| `authorization_required` | 需要用户当场授权（发消息 / 文件 / 账号），返回 `challenge` 与 `scope` | 停止动作，等用户确认后重试同一动作 |
| `authorization_pending` | 已有授权请求未处理，所有动作暂停 | 等待用户 |
| `internal_error` | 未预期异常 | 附消息 |

### 4.0 授权怎么走（`authorization_required`）

```json
{
  "ok": false,
  "code": "authorization_required",
  "challenge": "8f14e45f-…",
  "scope": "messaging",
  "message": "发送内容：发送",
  "observation": { ... }
}
```

调用方**不要**重试、也不要想办法"点掉"授权 —— AIDL 里没有授权方法，这是故意的：

- Lyra 的 UI 会弹出确认框；用户点「允许这一次」后，引擎在 60 秒内接受**同一个动作指纹**（动作 + 包名 + 元素文本）一次；
- 授权用掉即焚；过期或换元素都要重新问；
- 待授权期间，任何动作都会返回 `authorization_pending`（防止"先做别的再回来点允许"）。

`blocked` 与 `authorization_required` 的区别是本质的：前者没有解锁路径，后者只有人能解锁。


### 4.1 过期校验（防止点错）

每次动作前重新抓一次树，比较**调用方看到的元素**与**当前同编号元素**的指纹：

```
viewId + className + text + bounds + clickable
```

任一不同 → `stale_observation`。这比只比对 `obsId` 严格：同一 `obsId` 下屏幕也可能已变。

### 4.2 点击策略

1. 节点 `clickable` → `performAction(ACTION_CLICK)`；
2. 否则用 `dispatchGesture` 在元素中心点按（60ms），不阻塞等待回调；
3. 手势入队失败 → `action_failed`。

---

## 5. 调用序列（推荐）

```
obs = observe()                       # obsId=42
tap(id=8, obs=42)                     # → 返回 obsId=43 + 新观察
# 不需要再 observe()
scroll(0, 43)                         # → obsId=44 + 新观察
```

模型侧的工具映射（与另一会话的协议对齐）：

| 模型工具 | 服务调用 |
|---|---|
| `tap(id)` | `tap(id, currentObsId)` |
| `input(id, text)` | `setText(id, currentObsId, text)` |
| `scroll(dir)` | `scroll(dir, currentObsId)` |
| `back()` | `globalAction("back")` |
| `launch(app)` | `launch(packageName)` |

`obsId` 由协议层持有并随每次动作更新（用响应里的 `obsId`）。

---

## 6. 线程与生命周期

- AIDL 调用到达 binder 线程；引擎内部 `post` 到主线程执行，阻塞等待最多 8s。
- 动作后固定等待 260ms 再抓取，作为最朴素的 settle；后续会替换为「事件静默」判定。
- 引擎状态（上一次观察）在服务重连时会丢失：此时回退到 `AccessibilityState` 中公开的快照做校验，
  所以一次重连不会让所有动作失效。
- 应用切后台 / 进程被杀：绑定断开，`isReady()` 返回 false。

---

## 7. 开放到其他进程 / 其他应用

当前：

```xml
<service android:name="com.eloykim.lyra.automation.AutomationService"
         android:exported="false" />
```

要对外开放时：

```xml
<permission android:name="com.eloykim.lyra.permission.CONTROL"
            android:protectionLevel="signature" />

<service android:name=".automation.AutomationService"
         android:exported="true"
         android:permission="com.eloykim.lyra.permission.CONTROL" />
```

调用方需要：

```kotlin
val intent = Intent("com.eloykim.lyra.AUTOMATION").setPackage("com.eloykim.lyra")
context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

前提：调用方与本应用同签名（`signature` 级权限）。不要用 `normal` —— 那等于把读屏能力开放给任意应用。

---

## 8. 尚未实现（按优先级）

| 能力 | 说明 |
|---|---|
| `waitFor(condition, timeout)` | 目前靠调用方轮询 `observe()`；应改为事件驱动 |
| `settle` 判定 | 现在是固定 260ms；应为「事件静默 250ms 且无输入法动画」 |
| 截图 / OCR 降级 | 元素树稀疏时（Flutter/自绘）需要 |
| `tapAt(x, y)` | 视觉降级时才允许暴露 |
| 多窗口 / 分屏 | 现在只取 `rootInActiveWindow` |
| 敏感包黑名单 | 银行/支付/密码管理器应直接拒绝 |
| 动作后校验 | 目前只返回新观察，不判断「有没有生效」 |

---

## 9. 已验证

在 OnePlus PFJM10（Android 16 / API 36）上：

- 从「设置」页抓取 75 个节点 → 压缩后 9 条元素，`observe()` 正常返回；
- 在 Lyra 内点击检查器列表中的可点节点，经 AIDL 调用真实 `ACTION_CLICK`，界面成功返回上一页；
- 无障碍服务被系统关闭后，UI 状态与 `isReady()` 同步为 false。
