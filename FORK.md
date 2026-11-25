# dsh-Go · AbelDuan fork

上游：<https://github.com/ccch1mneyyy/dsh-Go>

本仓库只做一件事：**在上游之上叠一组本机（Xiaomi 折叠屏 2608BPX34C / Android 17 / HyperOS 4）的修复**，
并且让上游更新时可以合并，而不是重新打补丁。

| 分支 | 内容 | 当前 |
| --- | --- | --- |
| `main` | 上游镜像，只同步，不放任何本地修改 | `5ac52c368`（= 上游 main） |
| `custom` | 默认分支 = `main` + 本 fork 的修复 | `a7a3593b8`（`main` 的合并提交，含全部修复） |

---

## 1. 改动清单

每个条目：**症状 → 根因 → 改了什么（在哪） → 合并时注意什么 → 怎么验证**。

### 1.1 输入框上方状态条灰闪

- **症状**：输入框上方的状态条在多行轮换（上下文 / API 额度来回切）时，上半部发灰闪一下。
- **根因**：`ComposerStrip` 外层套了 `AnimatedSwitcher`，旧行淡出、新行淡入时两行同时在树里，
  各自带一层 `GlassSurface` 半透明玻璃，两层叠在同一位置就发灰。
- **改动**：`app/lib/ui/plugins/native/composer_strip.dart` —— 去掉外层 `AnimatedSwitcher`，
  直接换行；同一插件自己文字变化的 160ms 淡入（内层 `AnimatedSwitcher`）保留。
- **合并注意**：不变量是「文件里 `AnimatedSwitcher(` 恰好 1 处」。上游若又加一层切换/骨架屏，按同样原则别让两层玻璃重叠。
- **验证**：`grep -c 'AnimatedSwitcher(' composer_strip.dart` 为 1；真机在两个插件行之间来回切，看状态条不再灰闪。

### 1.2 屏幕边缘光效（EdgeBeam）四角与折痕

- **症状 ①**：折叠外屏（1168×1712，系统报 TL 28 / TR 154 / BR 154 / BL 28）左边两个近乎直角的角被画成大圆角，光带浮在玻璃里侧。
- **症状 ②**：光带在四个角上出现指向屏幕中间的「折痕」。
- **根因 ①**：`getRoundedCorner` 四角只取了**最大值**，用一个半径画整圈。
- **根因 ②**：周长坐标按「边」投影（`perimeterAt`），四个角上相邻两条边的投影位置会分岔。
- **改动**：
  - `phone/a11y/EdgeBeamModel.kt`：`EdgeBeamSpec.cornersPx` 返回 `FloatArray(4)`（TL,TR,BR,BL），
    四角各用自己的半径；周长公式 `2(w+h) − 2Σr + πΣr/2`。
  - `phone/a11y/EdgeBeam.kt`：路径用 `Path.addRoundRect(RectF, float[8])`；AGSL 用 `uniform float4 radii`
    按象限取半径算 SDF；位置改成绕屏幕中心的角度场 `perimeter * atan(p.x, -p.y)/(2π)`（顶边中点起顺时针，一圈连续）。
- **合并注意**（易冲突）：判边归类的条件必须是 `h.x - ax < h.y - ay`，**不能**写成 `ax > ay`——
  竖长屏会把右边误判成横边。内屏四角 142 不变。
- **验证**：`EdgeBeamModelTest`（一角为 0、混合半径下周长映射，1801 点误差 0）；真机折起来看外屏左侧两个角、内屏四角。

### 1.3 折叠换屏后桌宠跑到关掉的屏上

- **症状**：展开 / 折叠之后，桌宠出现在已经关掉的那块屏上（看不见），或干脆不出现。
- **根因**：给 overlay 建窗口用的 `WindowManager`/context 被缓存了，换屏后仍指向旧的默认显示。
- **改动**：
  - `pet/PetOverlay.kt`：`onConfigurationChanged` 里把 `appWindowContext` 清空（下次 attach 取新的）；
  - `phone/a11y/PhoneA11yService.kt`：`onConfigurationChanged` 里 `invalidateOverlayWindowContext()`。
- **合并注意**（易冲突）：上游在 `PetOverlay` 里加了 companion float（`dockedRest()` / `EdgeSnap.hug` /
  `tucking`），改这块时**必须基于 `custom` 当前文件**，别拿旧快照整文件覆盖（见 §4）。
- **验证**：展开—折叠来回切换，桌宠与悬浮鲸鱼都在当前屏的正确位置。

### 1.4 桌宠 / 鲸鱼吸附：贴物理边缘、按画面中线分左右、轻甩换边

- **症状 ①**：在摄像头那一侧的边，宠物往里避让，不贴边。
- **症状 ②**：拖到屏幕左半边不贴左、右半边不贴右，分界偏。
- **症状 ③**：要很使劲甩才换到另一边。
- **根因 ①**：`safeArea()` 把 `WindowInsets.Type.displayCutout()` 算进内边距；停靠又额外留了 8dp 横向边距。
- **根因 ②**：左右按**窗口**中心分，宠物包命中框常比画面宽很多，分界偏移。
- **根因 ③**：甩动阈值 1200 px/s。
- **改动**：
  - `pet/PetOverlay.kt`：`safeArea()` 只取 `systemBars()`（不计凹口）；所有停靠调用传 `marginX = 0`；
    companion float 的 `EdgeSnap.hug(..., inset = 0, ...)`（原来是 2dp 内缩）；`onDragEnd` 用
    `centerX = drop.x + (v.body.left + v.body.right) / 2f` 传画面中心。
  - `pet/EdgeSnap.kt`：新增 `sideFor(centerX: Float, area, velocityX)`；`dockFor(...)`、`position(...)`
    增加 `centerX` / `marginX` 参数；`FLING_PX_PER_S` 1200 → **700**。
  - `pet/PetView.kt`：新增 `val body: FramePx`（画面本体，相对 rest 原点）。
- **合并注意**：吸附入口已收拢到 `PetOverlay.dockedRest()`；上游若再改停靠，改那一个函数即可，
  别在调用点各改一遍。
- **验证**：`EdgeSnapTest`（贴边 `marginX=0`、按画面中线分左右、700 算甩动 / 400 不算）；真机拖到摄像头那条边松手。

### 1.5 文档与 CI

- `docs/design/phone-overlay.md`：同步 1.2 / 1.4 的描述（四角各按自己半径、鲸鱼贴边）。
- `.github/workflows/build-fixed-apk.yml`：本仓库自己的构建（见 §3）。
- `.github/workflows/sync-upstream.yml`：把上游 `main` 合进 `custom`（手动 / 每天 03:23）。

### 1.6 高 RTT 网络下"连模型一直重试 / 拉不到模型"

**症状**：只在某条宽带下（同一个路由器 2.4G / 5G 都一样）OpencodeGO 拉模型列表失败、选模型后一直重试；
换蜂窝或别的网络正常；同一把 key 在别处可用，额度也充足。

**根因**：Node 默认 `net.getDefaultAutoSelectFamily() === true`、
`net.getDefaultAutoSelectFamilyAttemptTimeout() === 250`（ms）。`fetch()`（undici）→ `net.connect`
走 happy-eyeballs，**每个候选地址只给 250 ms**，超时即换下一个，全部放弃后连接以 `ETIMEDOUT` 失败
（约 2×250 ms ≈ 520 ms 后返回）。该宽带出口到 opencode.ai（Cloudflare `104.18.10.66` / `104.18.11.66`）
实测 RTT 212–628 ms（5G 318–394 ms），而到国内 `223.5.5.5` 只有 13–17 ms —— RTT 一旦跨过 250 ms，
连接就必然被 Node 自己放弃。拉模型和对话走同一条连接路径，所以两者一起异常；引擎的 `TIMEOUT` 重试
5 次（退避 0.5→8 s）把每次失败放大成"一直重试"。

同网络同 key 15 次 `POST/GET /zen/go/v1/models` 的对照：

| 配置 | 成功 |
| --- | --- |
| 默认（250 ms 窗口） | 6/15、13/15 波动，失败都是 ~520 ms `ETIMEDOUT` |
| `--network-family-autoselection-attempt-timeout=1200/1500/2000` | 15/15 |
| `--no-network-family-autoselection` | 15/15 |
| 挂全局 VPN（TCP 在本地 tun 上握手） | 15/15 |

对照实验排除了网络层：python 直连 TCP 15/15（中位 306 ms）、TLS 握手 8/8、连上后 `/models`
返回完整 49407 字节。

**改了什么**

- `runtime/direct/proot-dns.mjs`：preload 里加
  `net.setDefaultAutoSelectFamilyAttemptTimeout(1500)`。这个文件被 `runtime/lib/rtbuild.py`
  原样铺到 rootfs 的 `/opt/dsh-go/direct/`，而引擎启动行固定带 `--import=/opt/dsh-go/direct/proot-dns.mjs`
  （Direct 引擎走 `DirectLaunch` 的 node wrapper，PRoot 兜底走 `DirectLaunch.prootNodeOptions`），
  所以两种启动方式都会加载它。
- `runtime/lib/rtbuild.py`：non-Direct 的 PRoot 启动模板里，`NODE_OPTIONS` 同时带上
  `--network-family-autoselection-attempt-timeout=1500`，终端里 `dsh` 子进程也跟着受益。
  该值必须和 preload 里的一致（1500）。

**合并时注意**：`runtime/direct/*` 会被放进 rootfs 的 `/opt/dsh-go/direct/`，属于运行时资产，
只有重新构建运行时（`runtime/build-runtime.sh`）才会带上；`build-fixed-apk.yml` 会自己构建运行时，
所以出 APK 不受影响。已经在旧运行时上装过的手机，可以用 `~/.dsh/patches/dsh-go-autoselect/apply.sh`
现打一次。

**怎么验证**：装新 APK 后，在同一条宽带下连续拉几次模型列表，`dsh` 日志里不应再出现
`fetch failed / ETIMEDOUT`（~520 ms 快速失败）；或直接跑
`node -p "require('node:net').getDefaultAutoSelectFamilyAttemptTimeout()"` 在 preload 里应为 1500。

### 1.7 边缘光效：直角屏两个角的 45° 缺一块 / 多一条折痕

**症状**：外屏边缘光效（`EdgeBeam`）绕屏一圈时，两个"直角"角（屏幕上报 28 px 的那两个角）出问题：
先是沿 45° 有一小块三角没被光晕覆盖、露出底下界面色；把角改成方角以后，又变成从角上 45° 延长出来
一条折痕，两侧过渡很不均匀。

**根因**（两件事，都在 8-bit 实测里对上了）：
1. **缺口**：外屏上报 `TL 28 / TR 154 / BR 154 / BL 28`，28 px 是厂商给"直角"的占位值，玻璃其实是方的。
   光带跟着这个圆弧走，圆弧沿 45° 比顶点内缩 `r(√2−1) ≈ 0.414r ≈ 12 px`，这段三角形既不画光、也不在
   玻璃之外 → 露底。实测（竖屏 1168×1712，根 `screencap`）：28 px 的角沿对角线第一颗亮像素在第 9 px，
   154 px 的角在第 45 px —— 正好 0.414r。
2. **折痕**：把半径直接改成 0 能补缺口，但着色用的是"到边界的距离"（`inset`），直角拐角的等值线是 L 形：
   贴两条边最亮，45° 对角线上离边界最远、最暗。半径取 0 后这条 L 一直延伸到顶点，肉眼就是从角上
   斜着伸出的一条暗线（实测 45° 垂直截面上亮度 212 → 163 → 202，一条 2–3 px 的凹线，只在角附近 150 px 内）。

**改了什么**：角仍然画方（补缺口），但**着色**用"光带自身宽度"把方角的距离圆滑掉：

- `EdgeBeamModel.kt`：`EdgeBeamSpec.cornersPx` 把小于一个光带宽（`WIDTH_DP`）的半径按 0 返回 ——
  路径与着色器都吃这个数组，所以两个直角角画到顶点、圆角那两个角一点不动。
- `EdgeBeam.kt` 的 AGSL：`r < 0.5` 时不再用矩形距离，改用两个方向边距的**平滑最小值**
  `inset = min(dx, dy) − w·ln(1 + e^{−|dx−dy|/w})`，`w = 0.7 × 光带宽`（`SOFT_CORNER`）。
  0.7 是"角上灌满的最亮区"和"过渡柔度"的折中：`w` 取 1.0 时光带宽 38.5 px、角上要对角走 26.7 px 才
  开始衰减；取 0.7（26.9 px）后降到 18.7 px，截面二阶差仍只有 0.002（肉眼无折痕）。贴边处与原来完全一致
  （`dx ≪ dy` 时第二项趋于 0），角上则被抬高成圆滑过渡：1 px 级的 L 形折痕没了，顶点仍然是最亮的点，
  而不是最远的点。数值验证（`WIDTH_DP=14`、density 2.75、`w=0.7`）：离角 40 px 的 45° 截面上原本 0.84→0.42→0.84
  的谷，改后是 0.90→0.80→0.90；离角 170 px 以上的剖面与原来差 ≤0.02，直边不受影响。
  实测 45° 截面（离角 85 px）二阶差 52.3 → 4.0，再收到 0.7w 后仍 ≤4。
- `EdgeBeamModelTest.theDisplaysCornerRadiiWinOverTheFallback`：28 的期望改成 0，另加 40（刚好超过一个
  光带宽）仍按圆角，钉住边界。

**合并时注意**：`cornersPx` 的归零阈值和 AGSL 里 `r < 0.5` 的分支是一对 —— 阈值改了，分支条件跟着改。
`EdgeRim.perimeter` 直接吃半径数组，两个直角角归零后周长会略变（5603 → 5628 px），光束速度按周长算，
不需要另外调。

**怎么验证**：控制手机期间用根 `screencap` 截外屏 —— ①沿两个直角角的对角线，第一颗亮像素应 ≤2 px
（原来第 9 px）；②沿 45° 垂直截面扫亮度，不应再有 2–3 px 的凹线。`EdgeBeamModelTest` 全绿。

### 1.8 输入法弹出后宠物被键盘挡住

- **症状**：键盘弹起来盖住宠物下半身；拖到底部时宠物还能停在键盘下面。
- **根因**：`SafeArea` 只减状态栏 / 导航栏，宠物不知道自己下面是键盘 —— 无障碍 overlay 不会被系统重排。
- **改动**：
  - `pet/EdgeSnap.kt`：新增纯函数 `SafeArea.aboveKeyboard(imeTop)`（把可用区底边抬到键盘上沿）与
    `EdgeSnap.aboveIme(at, h, imeTop, margin, floor)`（**只在键盘顶到时**上移，不按比例缩放位置）。
  - `pet/PetOverlay.kt`：`imeTop` 缓存（`IME_REFRESH_MS = 250`）由 `refreshIme()` 读两处来源 ——
    无障碍服务运行中读 `PhoneA11yService.instance.windows` 里 `TYPE_INPUT_METHOD` 的 `bounds.top`
    （悬浮 / 分体键盘取最上沿，和胶囊同一来源），否则 App 自己是焦点窗口时读
    `currentWindowMetrics.windowInsets` 的 `ime` inset；`dockedRest()` 结尾过 `clearOfIme()`；
    拖动、`向一句`、气泡改用 `usableArea()`；键盘高度变化时 `seat()` 把宠物平滑滑到新位置。
  - `phone/a11y/PhoneA11yService.kt`：`TYPE_WINDOWS_CHANGED` 事件里追加 `PetOverlay.onObstaclesChanged()`。
- **语义**：键盘收起后宠物回到原来的 dock（`yFraction` 不变），不记忆「被顶上去」的位置。
- **合并注意**：`refreshIme()` 是唯一读取点，别在别处再读 `windows` 或 inset；`usableArea()` 只用于
  「不许盖键盘」的场景，`safeArea()` 仍是 dock 的基准（这样键盘才只顶、不缩放）。
- **验证**：`EdgeSnapTest.aKeyboardLiftsThePetOnlyWhenItReaches` / `theAreaAboveTheKeyboardIsTheUsableOne`；
  真机在 DSH 或别的 App 里点输入框，宠物上移到键盘上方，收起键盘后回落原位。

### 1.9 锁屏界面还显示宠物

- **症状**：锁屏 / 亮屏未解锁时，宠物仍画在锁屏上（能看见、能点）。
- **根因**：`PetOverlay.interactive()` 把「屏幕亮」与「未锁屏」合成一个布尔，`OverlayMachine.rule()` 在
  `!interactive` 时返回 `PAUSED` —— 只停动画，窗口还在。
- **改动**：
  - `pet/OverlayPolicy.kt`：`OverlayMachine` 新增 `locked` 字段，`rule()` 在 `!interactive` **之前**
    判 `locked` 并返回 `HIDDEN("locked")`（走 `removeWindows()`，窗口真的移除，不是暂停）。
  - `pet/PetOverlay.kt`：`interactive()` 只判 `PowerManager.isInteractive`；新增 `locked()` 读
    `KeyguardManager.isKeyguardLocked`，在 `evaluate()` 里写进 `machine.locked`。
  - 触发链路已有的都复用：`ACTION_SCREEN_OFF / SCREEN_ON / USER_PRESENT` 广播（`screen` receiver）、
    无障碍 `TYPE_WINDOWS_CHANGED`（钥匙锁窗口出现/消失会来）。
- **语义**：熄屏 → `PAUSED`（保持进程、回来快）；锁屏 → `HIDDEN`（不留窗口）。解锁后由广播 + 每 2 s 的
  poll 恢复。
- **合并注意**：不要把 `locked` 合回 `interactive`；`hidden("locked")` 不带 `keepAlive`，锁屏时没必要为宠物留前台服务。
- **验证**：`OverlayMachineTest.theLockScreenTakesThePetAway`；真机按电源锁屏，`dumpsys window windows | grep "DSH 宠物"`
  应为空，解锁后重新出现。

### 1.10 手机壳层：中断不再阻断、内置 root 通道、shell 免确认

- **症状**：①上一次手机任务被中断后，之后每次调用手机工具都返回「上次手机任务中断…请先在手机操控页核对」，必须先点确认才能继续；②想给助手一条真正的 root 命令通道，只能装外部 KSU 模块 `dsh-root-relay`，模块或 `/data/local/tmp` 一被清就断；③`android_shell` 每条命令都要确认。
- **根因**：①`PhoneTaskJournal` 只写不读，遗留记录会让 `refusal()` 一直拦；②root 能力走的是外部中继二进制 + 模块开机脚本，与 App 自己的 `su` 授权无关；③`PhoneAndroidShell` 的免确认只看本任务授权，没看设置里的「始终允许」。
- **改动**：
  - `phone/shell/PhoneTaskJournal.kt`（`dsh_app` 的 phone 包）：发现遗留记录即 `acknowledgeInterrupted()` 自动清除，`status()` 多一个 `autoAcked` 计数；只有存储读写失败才继续报 `STORAGE_MESSAGE`。设置页的确认按钮保留（通常已无事可做）。
  - `phone/shell/ShellRootBackend.kt`：`su -c "CLASSPATH=<apk 路径> exec /system/bin/app_process /system/bin AndroidShellMain"`，把已有 job 协议原样包成 `ShellLink("root")`；`ShellProtocol.validUid` 增 `"root" -> uid == 0`。
  - `AndroidShellController` 的 `BACKENDS` 与 `PhonePlugin.configureAndroidShell` 白名单加 `root`；设置页多一行 **Root (su)**，无需配对，直接 Connect。
  - `PhoneAndroidShell.run` 新增 `autoApprove`，由 `PhoneCore.androidShell` 传设置里的「始终允许」；打开后 `android_shell` 不再逐条确认。
- **验证**：`/proc/<engine pid>/environ` 看 `--import`；真机设置里选 Root (su) → Connect → `android_shell id` 应输出 `uid=0(root)`；上次任务中断后直接调工具应成功。

### 1.11 root 中继内置：装 App + 给 Su 就够了

- **症状**：给 dsh-go 授了 Su，但在 Linux 环境里跑 root 命令仍然失败（`root-relay 不可达`），用户会以为授权没生效。
- **根因**：两件事被混在一起——App 自己的 `su` 通道（`ShellRootBackend`）是通的；助手 bash 用的中继（`~/.dsh/root-relay/rsh` → `127.0.0.1:37121`）却是仓库外的 KSU 模块 + `/data/local/tmp` 二进制，模块被删或 `/data/local/tmp` 被清就没了。
- **改动**：
  - `assets/rootrelay/dsr`：静态编译的 aarch64 中继（713 KB，源码 `AbelDuan/dsh-root-relay`）随 APK 走。
  - `phone/shell/RootRelay.kt`：每个进程跑一次，`su -c` 把 `dsr` 铺到 `/data/local/tmp/dsh-root/`、写 token/端口、`setsid nohup` 起监听，并把 token/port 写回 guest 的 `files/runtime/rootfs/root/.dsh/root-relay/`（`rsh` 读这里）。已在监听且 guest token 在 → 直接跳过；`/data/local/tmp` 被清或 runtime 重装后自动重建。
  - `DshService.onStartCommand` 开头调 `RootRelay.ensure(this)`（引擎起来时中继一起起来）；全程 `runCatching`，失败只记日志，不影响引擎。
- **语义**：不再需要 `/data/adb/modules/dsh-root-relay`；唯一前提是 App 持有 Su 授权。
- **验证**：`rm -rf /data/local/tmp/dsh-root` → 重启 App → `~/.dsh/root-relay/rsh 'id'` 应输出 `uid=0(root)`。

### 1.12 固定签名：每次出包都用同一把 key

- **症状**：CI 出的包 `pm install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名不一致），每次都得卸载重装，数据全丢。
- **根因**：`dsh-go-ci` 的 runner 每次生成新的 debug keystore，签名随机。
- **改动**：`tooling/keys/` 存入 `dsh-go-release.keystore`（PKCS12，alias `dshgo`，RSA 4096，`CN=dsh-go`，证书 SHA-256 `acd9272d…d49908`）、`password.txt`、`README.md`；`dsh-go-ci` 的 `build.yml` 优先用仓库内这把 key 写 `app/android/key.properties` 并 `keytool -list` 自检，secrets 只作兜底。
- **注意**：workflow 是 job 级 `working-directory: dsh-Go`，路径必须写成 `$(pwd)/tooling/keys/...`，用 `$GITHUB_WORKSPACE` 会拼错一层。
- **验证**：`java -jar apksigner.jar verify --print-certs app.apk` 的 DN 应为 `CN=dsh-go`。

---

## 2. 上游更新后怎么合

1. **不需要再点 Sync fork**：`AbelDuan/dsh-go-ci` 里的 **Sync upstream into custom** 会自己跑
   （每天 03:23，也能手动 Run workflow）。它做三件事：把 fork 的 `main` 快进到上游 `main` → 把上游
   `main` 合进 `custom` → 合并成功就顺手 dispatch 出包任务（`build` 输入传 false 则只合并不出包）。
2. **有冲突时任务失败**，`custom` 一个字节都不动，job summary 里列出冲突文件（`main` 镜像照样更新）；
   下次照下面本地解决。fork 自己的 `sync-upstream.yml` 是历史遗留，**fork 的 Actions 已被禁用，靠不住**。

> `dsh-go-ci` 需要仓库 secret `DSH_GO_PAT`（对 `AbelDuan/dsh-Go` 有 contents:write 的 token）才能推
> `custom`；secret 于 2026-11-25 写入，token 只存在 GitHub 的加密 secret 里。
3. 有冲突时本地解决（`custom` 上原有的修复要留下，冲突几乎总在下面这几个文件）：

```sh
git clone -b custom git@github.com:AbelDuan/dsh-Go.git
cd dsh-Go
git remote add upstream https://github.com/ccch1mneyyy/dsh-Go.git
git fetch upstream && git merge upstream/main
# 解决冲突后
git push origin custom
```

**冲突热点（历史都在这几个文件里）**

| 文件 | 冲突原因 |
| --- | --- |
| `pet/PetOverlay.kt` | 上游活动频繁（companion float、停靠、气泡都在这），本地改的是 `safeArea()`、`dockedRest()`、`onDragEnd` |
| `pet/EdgeSnap.kt` / `EdgeSnapTest.kt` | 本地加了 `centerX` / `marginX` 参数、`sideFor` 重载、阈值 700 |
| `phone/a11y/EdgeBeam*.kt` | 四角半径与角度场周长 |
| `pet/OverlayPolicy.kt` | 本地加了 `locked` 字段与 `rule()` 里的锁屏分支 |

**合并时的检查清单**

1. `pet/PetOverlay.kt` 里保留：`safeArea()` 只用 `systemBars()`；`dockedRest()` 的 `marginX = 0` 与 `hug(..., 0, ...)`；
   `onDragEnd` 的 `centerX`；`onConfigurationChanged` 里清空 `appWindowContext`。
2. `EdgeSnap.kt` 里保留：`sideFor(centerX: Float, …)` 重载、`dockFor`/`position` 的 `centerX`/`marginX`、
   `FLING_PX_PER_S = 700f`、`hug` 的 `inset` 语义。
3. `EdgeBeam*` 里保留：`cornersPx` 的 `FloatArray(4)`、角度场周长、`h.x - ax < h.y - ay` 的判边条件、
   `cornersPx` 里"小于一个光带宽的半径归 0"（§1.7），以及 `EdgeBeam.kt` 里 `r < 0.5` 用平滑最小值
   `min(dx,dy) − 0.7w·ln(1+e^{−|dx−dy|/0.7w})` 的那条分支。缺一个：归零但不平滑 → 折痕；平滑但不归零 → 缺口。
4. `pet/OverlayPolicy.kt` 里保留 `locked` 字段、`rule()` 中 `locked → hidden("locked")` 在 `!interactive`
   之前；`PetOverlay` 里保留 `interactive()`（只判屏幕）与 `locked()`（判钥匙锁）分开。
5. `PetOverlay` 的键盘避让：`refreshIme()` 单一来源、`clearOfIme()`/`usableArea()`、
   `onObstaclesChanged()`，以及 `PhoneA11yService` 的 `TYPE_WINDOWS_CHANGED` 里那一行调用。
6. `PhoneA11yService.onConfigurationChanged` 里保留 `invalidateOverlayWindowContext()`。
7. `OverlayPolicy.kt` 的 `locked` → `PetOverlay.locked()`（`KeyguardManager.isKeyguardLocked`）、
   `PetOverlay` 的 `refreshIme()` / `clearOfIme()` / `usableArea()` / `onObstaclesChanged()`、
   `EdgeSnap.aboveKeyboard()`+`aboveIme()`。注意键盘高度取自
   `android.view.accessibility.AccessibilityWindowInfo`（**不是** `android.accessibilityservice`，写错就编译不过）。
8. `composer_strip.dart` 里 `AnimatedSwitcher(` 仍然只有 1 处。
9. `runtime/direct/proot-dns.mjs` 里保留 `net.setDefaultAutoSelectFamilyAttemptTimeout(1500)`（§1.6），
   `runtime/lib/rtbuild.py` 的 `NODE_OPTIONS` 里保留 `--network-family-autoselection-attempt-timeout=1500`。
   上游若重写了这两个文件，按 §1.6 重新加一遍（一行 + 一处环境变量）。
10. 合并后必然要能的编译验证已经内置：`dsh-go-ci` 的 sync 任务合并完成后会 dispatch `build.yml`，
    编译在 Actions 里跑（Kotlin 单测不一定全跑，至少保证能编过）；只改文档不会触发，需要时手动
    Run workflow。

### 2.1 让 AI 合并与出包（本机固定流程）

本机（aarch64 proot）出不了 APK，也没有 github.com 直连；走 API + Actions，口令是「合并上游并出包」。
固定顺序（2026-11-25 按现状重写）：

1. 触发 `AbelDuan/dsh-go-ci` 的 sync：`POST /repos/AbelDuan/dsh-go-ci/actions/workflows/429799995/dispatches`
   （body `{"ref":"main","inputs":{"build":true}}`）。它负责快进 `main` 镜像、合并 `custom`、dispatch 构建。
   想先看差异再动：`GET /repos/AbelDuan/dsh-Go/compare/<upstream-main-sha>...<custom-sha>`，`behind_by > 0`
   就是落后的提交数。
2. 合并冲突时（sync 任务失败）：用 API 读两侧文件、按 §2 清单解决，`POST /git/trees` + `/git/commits`
   推回 `custom`（tree entry 的 `type` 必须写 `blob`，写 `file` 会 500）；推完再手动 dispatch `build.yml`。
3. 构建在 `AbelDuan/dsh-go-ci` 的 `build.yml`（id 429765289）跑，约 25–30 分钟；产物是 artifact
   `dsh-go-arm64`（约 227 MB，zip 里就一个 `app-arm64-release.apk`）。
4. 取产物（本机实测 ~1 MB/s）：先带 PAT 请求
   `GET /repos/AbelDuan/dsh-go-ci/actions/artifacts/<ID>/zip` 并**禁止跟随重定向**拿到 `Location`，
   再用**不带** Authorization 的请求 GET 那个 Azure 地址（带 token 会 401）。
5. 装机：`cp` 到 `/data/local/tmp/dshgo.apk` → `pm install -r -d /data/local/tmp/dshgo.apk` →
   删临时文件（system_server 读不了 `/sdcard` 的 fuse）。App 进程会被杀一次，重开生效。
6. 装机后自查：`dumpsys package io.github.ccch1mneyyy.dsh_app | grep versionCode`；
   `files/runtime/rootfs/opt/dsh-go/direct/proot-dns.mjs` 里应有 `1500`。

---

## 3. 出 APK

> **2026-11-25 起：本仓库（fork）的 Actions 被 GitHub 禁用**，`workflow_dispatch` 返回
> `422 Actions has been disabled for this repository`，push 也不再产生任何 run。
> 起因是某次 push 里带了 `.github/workflows/` 的改动（哪怕只加一行注释）——fork 的 workflow
> 一变，GitHub 就要仓库所有者到网页上重新批准。`/actions/permissions` 仍显示 `enabled: true`、
> workflow `state: active`，`PUT .../workflows/{id}/enable` 返回 204 也不解除，只能网页点一次。
> **日常构建改走独立仓库 [`AbelDuan/dsh-go-ci`](https://github.com/AbelDuan/dsh-go-ci)**
> （普通仓库、非 fork、Actions 正常）：它 clone `AbelDuan/dsh-Go@custom` 后跑和下面完全相同的
> 步骤，产物是 artifact `dsh-go-arm64`。教训：**不要为了触发构建去改 fork 里的 workflow 文件。**

本仓库自己的 workflow（网页批准后仍可用）：

Actions → **Build fixed APK** → Run workflow（推 `custom` 也会自动跑；workflow 名在 Actions 列表里可能显示为 **CI**）。

- 产物：release [`strip-fix`](https://github.com/AbelDuan/dsh-Go/releases/tag/strip-fix) 的 `app-arm64-release.apk`，
  以及 run 页的 `dsh-go-arm64-strip-fix` artifact。
- 构建参数：`tooling/build-apk.sh -f arm64 --no-companion --no-star`。
  **`--no-companion`**：仓库里没有授权的桌宠素材包，装出来没有桌宠，其余功能不变。
> **2026-11-25 起签名固定**：`tooling/keys/dsh-go-release.keystore`（alias `dshgo`，`CN=dsh-go`）
> 已入库，`dsh-go-ci` 的 `build.yml` 优先用它写 `app/android/key.properties` 并 `keytool -list` 自检。
> 出包证书恒为 `SHA-256 acd9272d…d49908`，`pm install -r` 直接覆盖，**不需要卸载**。
> secrets（`KEYSTORE_B64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）只作兜底。
> 换 key 会让已装机器装不上（除非系统能跨签名覆盖），所以这把 key 不要丢、不要换。
- CI 环境注意（Linux runner）：SDK 装到 `$HOME/android-sdk`（runner 镜像里的 SDK 不可写）；
  `tawcroot` 要求 NDK **28.2.13676358**，直接用 google 的 `android-ndk-r28c-linux.zip`（`sdkmanager` 装出来的缺 `source.properties`）。

**装机（本机）**

```sh
cp app-arm64-release.apk /data/local/tmp/dshgo.apk   # system_server 读不了 /sdcard（fuse avc）
pm install -r -d /data/local/tmp/dshgo.apk
rm -f /data/local/tmp/dshgo.apk
```

---

## 4. 别用旧快照改文件

本机 `/root/workspace/dsh-Go` 是 2026-11-23 的快照，**比上游旧**。
2026-11-24 用这个快照整文件推送，静默回退了上游对 `PhoneA11yService.companionScreenSample` 的改动，
CI 报 `CompanionScreenBridge.kt:118 Too many arguments`。

改文件的正确做法：用 GitHub contents API 取 `custom` 上的**当前**文件 → 在上面改 → 整文件提交，
提交后核对 commit 的 additions / deletions 是不是只包含自己的改动。

## 5. 合并历史

| 时间 | 上游 | 结果 |
| --- | --- | --- |
| 2026-11-25 | 无（本 fork 自身） | `1.10` 手机壳层三项（中断自动解除 / 内置 root shell / shell 尊重「始终允许」）、`1.11` root 中继内置进 APK、`1.12` 固定签名入库；装机 `11260045`（1.4.0） |
| 2026-11-25 | `d73c679c3`（175 提交 / 165 文件） | 合并：`OverlayPolicy.kt`、`PetOverlay.kt`、`PhoneA11yService.kt` 三方合并**无冲突**（本 fork 对这三个文件的改动与上游不在同一处）；`EdgeSnap.kt`、`PetView.kt`、`EdgeBeam*`、`composer_strip.dart` 上游未动，直接用 `custom` 版本。合并后新增 §1.8 键盘避让与 §1.9 锁屏不显示 |
| 2026-11-24 | `db9452d9` | fork 建立，`custom` = 上游 + 修复提交（`ba37627` 起） |
| 2026-11-24 | `5ac52c368`（75+1 提交，146 个文件） | 合并提交 `a7a3593b8`；冲突 3 处，全在 `pet/PetOverlay.kt`（`onDragEnd` / `onDragCancel` / `askFresh`）：按上游新增的 `dockedRest()` 与 companion float 分支保留双方改动；`EdgeSnap.kt`、`EdgeSnapTest.kt` 三方合并无冲突 |

## 6. 本机补丁备份

`~/.dsh/patches/dsh-go/` 下留了补丁版和说明（`strip-no-crossfade.patch` 等），
上游结构变化导致 merge 特别麻烦时可以直接 `git apply -3`。
