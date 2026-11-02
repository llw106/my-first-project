# 微信步数修改（sport_step）

实现文件：

- `app/src/main/java/h/Hchat/hooks/items/sportstep/SportStepFeature.kt`
- `app/src/main/java/h/Hchat/hooks/items/sportstep/SportStepSettings.kt`
- `app/src/main/java/h/Hchat/hooks/items/sportstep/SportStepSettingsProvider.kt`

算法一比一对齐开源模块 `io.github.yylsping.wechatantirecall`（`WeChatEnhancements#installSportModifierHook`），
没有"目标步数""平滑爬升""覆盖文件"这类额外逻辑。

## 一、唯一 Hook 落点

```
android.hardware.SystemSensorManager$SensorEventQueue#dispatchSensorEvent
签名：(I[FIJ)V   // args[0]=handle, args[1]=values, args[2]=accuracy, args[3]=timestamp
```

流程：

1. `args[0]` 拿到 `handle`；
2. 反射读 `this.mSensorsEvents`（`SparseArray<SensorEvent>`），`get(handle)` 反查事件；
3. 反射读 `SensorEvent.sensor`，取 `sensor.type`；
4. 只处理 `TYPE_STEP_COUNTER(19)` 与 `TYPE_STEP_DETECTOR(18)`，其它类型原样放过；
5. 改 `values[0]` 后交回原方法。

## 二、算法

| 类型 | 倍率模式（`MODE_MULTIPLY`=1） | 每步增加模式（`MODE_AUTO`=0，即插件的 auto） |
|---|---|---|
| `TYPE_STEP_COUNTER(19)` | `out = raw * value` | `offset += (raw - last) * value`；`out = raw + offset` |
| `TYPE_STEP_DETECTOR(18)` | `out = raw * value` | `out = raw + value` |

`raw < last`（重启、跨天、计数器归零）时 `offset` 归零。

**为什么不一次性跳到目标值：** 微信算的是"今日增量"，累计值突然跳变会被当成异常丢弃；
只有"每走一步按倍数补一点"的平滑增量才会被正常计入，所以是实时生效的。

`out` 必须有限且非负，否则直接放弃改写。

## 三、配置

存储：FastKV，文件名 `Hchat_sport_step_config`（位于宿主进程私有目录 `Hchat/` 下）。

| key | 含义 |
|---|---|
| `sport_step_enable` | 总开关 |
| `sport_step_mode` | `1` 倍率 / `0` 每步增加 |
| `sport_step_value` | 倍率或每步增量（字符串） |
| `sport_step_algo_ver` | 算法版本，`2` = 已迁移到插件语义 |

`SportStepSettings.migrate()`：`algo_ver < 2` 时把旧的"目标步数"配置重置为倍率 10，
因为旧值（如 9050 / 80000）在新算法下会变成天文数字。

## 四、两个必须知道的坑

### 1. 配置不能跨进程直接读 FastKV —— 用文本快照

FastKV 是 mmap 存储，跨进程写入后本进程看不到新值（微信进程读到 `en=false`）。

**但不能用"每 2 秒 reopen 一次"来解决**：设置界面用 `remember` 持有 FastKV 实例，
reopen 会把它 `close()` 掉，用户随后的保存全部写进已关闭的对象，
现象就是**改成 50 倍、重启微信又变回 5 倍**。

现在的做法：

- 设置界面每改一项，就调用 `SportStepSettings.publish()` 把配置拍成纯文本
  `Hchat/sport_step_runtime.txt`（`en=1` / `mode=1` / `value=50`）；
- 钩子用 `SportStepSettings.runtime()` 读这个文本（2 秒节流），完全不碰 FastKV 实例；
- 文本不存在时（例如刚升级还没进过设置页）才退回 FastKV 读，并且**绝不 close**。
- `migrate()` 只在**快照还不存在**时补写一份兜底；快照存在时绝不回写。

> **为什么钩子进程不能写快照**：设置界面跑在微信主进程，钩子跑在另一个进程。
> 钩子进程里的 FastKV 是它启动时加载的缓存，如果每次挂载都 `publish()` 回写，
> 就会把用户刚存的新倍数（50）覆盖成缓存里的旧值（5）—— 表现为"改完能用，重启微信又变回去"。

```kotlin
val snapshot = SportStepSettings.runtime(ctx)   // 优先文本快照
val sp = HchatStorage.preferences(ctx, PREFS_NAME)  // 兜底，不 reopen
```

### 2. MIUI 只在微信处于前台时投递计步事件

`dumpsys sensorservice` 可以看到微信的计步连接
`com.tencent.mm.plugin.sport.model.a0`：

- 微信在后台：`has sensor access: false` → 一条 `step_counter` 事件都不给，插件同样收不到；
- 微信切到前台：立刻变成 `has sensor access: true`。

所以"走了没反应"先看两点：**微信是否在前台**、**日志里有没有 `type=19`**。
后者为 0 说明事件根本没到，与算法无关。

## 五、验证方式

LSPosed 日志（`/data/adb/lspd/log/modules_*.log`）里应出现：

```
[Hchat:SportStep] 已挂载 传感器分发=1
[Hchat:SportStep] type=19 raw=303.0 -> 1515.0
```

（只在真正发生改写时打日志，10 秒节流；`传感器分发=0` 表示 hook 没挂上。）

微信侧 `files/mmkv/SportKv` 的 `KEY_STEP_DETAIL_INFO` 会同步按倍数变化。

## 六、上游（作者）更新后如何重新接入

本功能是**纯新增**：只新增 3 个文件 + 6 处接线，不修改作者已有的任何逻辑。
所以作者发新版本时，只要把下面这张清单重新贴一遍即可，**不需要重新适配微信的混淆类**。

### 1. 三个新文件（自包含，无额外依赖）

```
app/src/main/java/h/Hchat/hooks/items/sportstep/
├── SportStepFeature.kt            // 钩子主体：唯一落点 + 两种算法
├── SportStepSettings.kt           // 配置读写 + 纯文本快照下发
└── SportStepSettingsProvider.kt   // 设置项元数据（标题/描述/分类）
```

### 2. 注册功能：`hooks/core/FeatureRegistry.kt`

```kotlin
import h.Hchat.hooks.items.sportstep.SportStepFeature   // 加在 import 区
...
.register(SportStepFeature())                            // 加在 register 链里
```

### 3. 设置页接线：`ui/miuix/MiuixSettingsPage.kt`（4 处）

| 位置 | 内容 |
|---|---|
| import 区 | `import h.Hchat.hooks.items.sportstep.SportStepFeature` |
| 搜索关键词分支 | `SportStepFeature.ID -> listOf("步数", "微信运动", "刷步", "计步", "运动步数")` |
| 页面分发分支 | `SportStepFeature.ID -> SportStepMiuixPage(context, provider, onBack)` |
| 页面组件本体 | `@Composable private fun SportStepMiuixPage(...)`，放在任意其他 `*MiuixPage` 旁边即可 |

### 4. 依赖的框架能力（上游自带，升级后确认仍在）

- `BaseFeature` / `FeatureContext` / `HookRegistry`（`hooks/core`）
- `HchatStorage.preferences()` / `HchatStorage.storageDir()`（`preferences`）
- `HLog`（`utils`）
- `SimpleFeatureSettingsProvider` / `FeatureSettingsProvider.CATEGORY_ENHANCE`（`ui`）

### 5. 为什么这个方案抗升级

| 依赖点 | 是否随微信升级变化 |
|---|---|
| `android.hardware.SystemSensorManager$SensorEventQueue` | 系统类，不随微信变 |
| 字段 `mSensorsEvents`、`SensorEvent.sensor` | 系统字段，只在 Android 大版本改实现时才可能变 |
| 微信侧的类名/方法名 | **完全不用** —— 本方案不碰任何微信混淆类 |

也就是说：微信升级（甚至换版本重打包）**不会**让这个功能失效；
只有 Android 系统改传感器内部结构时才需要改字段名，届时日志会先暴露成 `已挂载 传感器分发=0`。

### 6. 重新接入后的验证

1. 编译安装 → 打开微信 → 看 LSPosed 日志有没有 `已挂载 传感器分发=1`；
2. 微信**前台亮屏**走 20~30 步 → 日志出现 `type=19 raw=... -> ...`；
3. 设置页改倍数 → 确认 `Hchat/sport_step_runtime.txt` 里 `value=` 跟着变。

## 七、风险

- 修改微信运动数据可能触发账号风控，倍数越大越明显。
- Hook 用默认优先级挂在系统 API 上，与其他改传感器的模块可能互相覆盖。
- 微信版本升级后类名/字段名变化会静默失效（全部 `runCatching` 兜住，只在日志里体现）。
