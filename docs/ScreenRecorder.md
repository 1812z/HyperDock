# HyperIsland 屏幕录制跨应用 API — 接入文档

供其他 AI / 开发者把"录屏状态读取 + 录屏控制 + 一键动态照片"接入到任意 Android 应用（也被安全中心侧边栏 Hook 使用）。

> 约束：整套实现只使用框架 API 与小米稳定标识（`miui.intent.screenrecorder.RECORDER_SERVICE`、`is_start_immediately`、`stop_screenrecorder`、`miui.screenrecorder.resolution/sound`），**禁止**依赖任何混淆类名/方法名。

---

## 1. 组件与协议

| 项 | 值 |
|---|---|
| 模块包名 | `io.github.hyperisland` |
| 控制服务类 | `io.github.hyperisland.screenrecorder.ScreenRecorderControlService` |
| 绑定 action | `io.github.hyperisland.action.SCREEN_RECORDER_CONTROL` |
| 权限（第三方） | `io.github.hyperisland.permission.CONTROL_SCREEN_RECORDER`（`normal` 级，安装即授予） |
| 安全中心 | `com.miui.securitycenter` 无需权限，默认放行 |
| 通讯方式 | `Messenger`（基于 `Bundle`，非 AIDL），服务在 `:screen_recorder_control` 进程 |

绑定方式（显式包名）：

```java
Intent i = new Intent("io.github.hyperisland.action.SCREEN_RECORDER_CONTROL")
        .setPackage("io.github.hyperisland");
context.bindService(i, conn, Context.BIND_AUTO_CREATE);
```

被 Hook 进安全中心进程的代码，可直接复用模块内的参考实现
`io.github.hyperisland.screenrecorder.ScreenRecorderApi`（无需自己拼 Messenger）。

---

## 2. 复用的参考客户端 `ScreenRecorderApi`

```kotlin
// 连接并订阅状态
val conn = ScreenRecorderApi.connect(context, object : ScreenRecorderApi.Listener {
    override fun onState(snapshot: RecorderSnapshot) {
        // 每次变化都会回调；snapshot.state / durationAt(now) 见第 4 节
    }
    override fun onResult(success: Boolean, error: String?) {
        // 控制请求的确认；error: already_active / not_recording / not_paused
        //        / not_active / unknown_op / start_failed
    }
})

// 查询一次（可选，注册时已自动推送一次）
conn.query()

// 控制
conn.start(ScreenRecorderApi.StartOptions(motionPhoto = true)) // 一键动态照片
conn.start(ScreenRecorderApi.StartOptions(resolution = "1920*1080", sound = 2))
conn.start()          // 全部跟随录屏旧设置
conn.pause()
conn.resume()
conn.stop()

conn.close()
```

具备系统权限的调用方（安全中心自身）可**不经过模块服务**直接拉起录屏，冷启动更可靠：

```kotlin
// 一键动态照片
ScreenRecorderApi.startMotionPhotoDirect(context)

// 自定义参数
context.startService(
    ScreenRecorderApi.buildStartIntent(
        ScreenRecorderApi.StartOptions(resolution = "1920*1080", sound = 1, motionPhoto = true)
    )
)
```

> 是否用直连取决于权限/UID：若 `com.miui.screenrecorder` 与调用方同属 `android.uid.system`，模块进程直接 `startService` 会被系统拒绝；此时用 `buildStartIntent(...)`。普通第三方应用请用 `conn.start(...)`。

---

## 3. 控制协议（手写 Messenger 时）

服务端只接受 `what` ∈ `20..25`，非法发送方会被静默丢弃。

| 消息码 | 名称 | 方向 | 说明 |
|---|---|---|---|
| 20 | `API_MSG_REGISTER` | 客户端→服务 | 注册，后续状态推送 |
| 21 | `API_MSG_UNREGISTER` | 客户端→服务 | 注销 |
| 22 | `API_MSG_QUERY` | 客户端→服务 | 查询一次状态 |
| 23 | `API_MSG_STATE` | 服务→客户端 | 状态快照（关键字段见下） |
| 24 | `API_MSG_CONTROL` | 客户端→服务 | 下发操作 |
| 25 | `API_MSG_RESULT` | 服务→客户端 | 操作结果 ack |

发送时必须带 `replyTo`（用于接收 23/25）：

```java
Message m = Message.obtain(null, 24); // API_MSG_CONTROL
Bundle b = new Bundle();
b.putString("op", "start");
b.putString("resolution", "1920*1080"); // 可选
b.putInt("sound", 2);                   // 可选，见第 5 节
b.putBoolean("motion_photo", true);     // 可选，一键动态照片
m.setData(b);
m.replyTo = incomingMessenger;
remote.send(m);
```

op 取值：`start` / `pause` / `resume` / `stop`。

---

## 4. 状态字段（`API_MSG_STATE` 的 Bundle）

| key | 类型 | 含义 |
|---|---|---|
| `state` | int | 0 空闲 / 1 准备中 / 2 录制中 / 3 已暂停 |
| `duration_millis` | long | 快照时刻的累计录制时长（暂停期间不增长） |
| `snapshot_elapsed` | long | 快照对应的 `SystemClock.elapsedRealtime()`，用于算实时时长 |
| `started_at_wall_clock` | long | 本次会话开始时的 `System.currentTimeMillis()` |

实时时长算法（跨进程安全，`elapsedRealtime` 全局一致）：

```kotlin
val live = duration_millis + if (state == 2) {
    (SystemClock.elapsedRealtime() - snapshot_elapsed).coerceAtLeast(0)
} else 0L
```

`API_MSG_RESULT` 在同样字段基础上附加：
- `success: Boolean`
- `error: String?`（失败时存在）

---

## 5. 参数语义（"不传即跟随旧设置"）

`start` 的 `Bundle` 中，**缺省/为 null 的字段一律不改动录屏应用原有设置**：

| key | 类型 | 说明 |
|---|---|---|
| `resolution` | String | 分辨率，如 `1920*1080`；空串按未提供处理 |
| `sound` | int | 0 无声 / 1 麦克风 / 2 设备声音 / 3 设备+麦克风；传 1/3 时由录屏应用按需请求麦克风权限 |
| `motion_photo` | Boolean | `true` 表示本次录制保存为动态照片（仅作用本次，最长约 30 秒，成功后删除原 MP4） |

参数由录屏进程内的 Hook 在启动前写入 `com.miui.screenrecorder_preferences` 的
`miui.screenrecorder.resolution` / `miui.screenrecorder.sound` 并 `MotionPhotoSession.arm(...)`。

---

## 6. 鉴权规则

`ScreenRecorderControlService.isAllowedApiUid(uid)`：

1. 模块自身 UID → 放行
2. UID 拥有 `com.miui.screenrecorder` 包 → 放行
3. UID 拥有 `com.miui.securitycenter` 包 → 放行
4. 其他 → 需 `io.github.hyperisland.permission.CONTROL_SCREEN_RECORDER` 被授予

第三方应用清单声明：

```xml
<uses-permission android:name="io.github.hyperisland.permission.CONTROL_SCREEN_RECORDER" />
```

---

## 7. 生命周期与注意事项

- 服务进程独立（`:screen_recorder_control`，`stopWithTask=false`），绑定用 `BIND_AUTO_CREATE` 即启动。
- 外部客户端与内部录屏客户端分表管理；**外部客户端断开不会强制清零会话状态**。
- `state=1`（准备中）若 20 秒内未收到录制确认会自动回落为 `0`。
- 查询/控制必须持有 `replyTo`；服务通过 `Message.sendingUid` 鉴权。
- 录屏进程不在线时：服务会尝试直接 `startService` 冷启动；失败返回 `error=start_failed`，有系统权限请改用 `buildStartIntent(...)`。
- 该功能依赖模块屏幕录制 Hook 已启用（`pref_screen_recorder_island`）；未启用时状态始终为 idle 且参数不会生效。

---

## 8. 最小接入清单

1. 清单声明权限（第三方）绑定 action。
2. `bindService` → `onServiceConnected` 拿 `Messenger` → 发 `API_MSG_REGISTER`。
3. 保存 `replyTo` Messenger，处理 `API_MSG_STATE` / `API_MSG_RESULT`。
4. 需要录制时发 `API_MSG_CONTROL`（`op=start` + 可选参数）。
5. 退出时 `API_MSG_UNREGISTER` + `unbindService`（或 `Connection.close()`）。

需要的话我可以把这份文档落盘到 `docs/` 下（例如 `docs/screen-recorder-api.md` 并挂到 VitePress 侧栏）。

---
