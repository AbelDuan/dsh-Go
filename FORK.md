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

---

## 2. 上游更新后怎么合

1. 仓库首页 **Sync fork**（把 `main` 同步到上游最新）。
2. Actions → **Sync upstream into custom** → Run workflow（每天 03:23 也会自动跑）。
   没有冲突就直接推回去；**有冲突任务失败，`custom` 不动**。
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

**合并时的检查清单**

1. `pet/PetOverlay.kt` 里保留：`safeArea()` 只用 `systemBars()`；`dockedRest()` 的 `marginX = 0` 与 `hug(..., 0, ...)`；
   `onDragEnd` 的 `centerX`；`onConfigurationChanged` 里清空 `appWindowContext`。
2. `EdgeSnap.kt` 里保留：`sideFor(centerX: Float, …)` 重载、`dockFor`/`position` 的 `centerX`/`marginX`、
   `FLING_PX_PER_S = 700f`、`hug` 的 `inset` 语义。
3. `EdgeBeam*` 里保留：`cornersPx` 的 `FloatArray(4)`、角度场周长、`h.x - ax < h.y - ay` 的判边条件、
   `cornersPx` 里"小于一个光带宽的半径归 0"（§1.7），以及 `EdgeBeam.kt` 里 `r < 0.5` 用平滑最小值
   `min(dx,dy) − 0.7w·ln(1+e^{−|dx−dy|/0.7w})` 的那条分支。缺一个：归零但不平滑 → 折痕；平滑但不归零 → 缺口。
4. `PhoneA11yService.onConfigurationChanged` 里保留 `invalidateOverlayWindowContext()`。
5. `composer_strip.dart` 里 `AnimatedSwitcher(` 仍然只有 1 处。
6. `runtime/direct/proot-dns.mjs` 里保留 `net.setDefaultAutoSelectFamilyAttemptTimeout(1500)`（§1.6），
   `runtime/lib/rtbuild.py` 的 `NODE_OPTIONS` 里保留 `--network-family-autoselection-attempt-timeout=1500`。
   上游若重写了这两个文件，按 §1.6 重新加一遍（一行 + 一处环境变量）。
7. 跑一次 **Build fixed APK** 验证编译（Kotlin 单测在 CI 的 `Build the APK` 之前不一定全跑，冲突后至少要能编过）。

### 2.1 让 AI 合并与出包（本机固定流程）

本机（aarch64 proot）出不了 APK，也没有 github.com 直连；走 API + Actions，口令是"合并上游并出一个新 APK"。
我会按这个顺序做，做完把 APK 装到手机上：

1. `POST /repos/AbelDuan/dsh-Go/merge-upstream` 或手动 `Sync fork`（`main` ↔ 上游），再触发
   `POST /actions/workflows/sync-upstream.yml/dispatches {"ref":"custom"}`。
2. 冲突时：用 `git` 拉 `custom` + `upstream/main`，按 §2 的检查清单解决，`POST /git/trees` 建提交推回 `custom`
   （注意 tree entry 的 `type` 必须写 `blob`，写 `file` 会 500）。
3. 推 `custom` 自动触发 **Build fixed APK**（改到 `app/**`、`runtime/**`、`tooling/**` 才会触发；
   只改文档不会，这时候用 `workflow_dispatch` 手动跑）。
4. 成功后从 release `strip-fix` 的 `app-arm64-release.apk` 附件下载（229 MB，走 `api.github.com` 的资产地址），
   `cp` 到 `/data/local/tmp` 再 `pm install -r -d`（system_server 读不了 `/sdcard` 的 fuse），
   App 进程会被杀一次、重启后生效。
5. 装机后自查：`dumpsys package io.github.ccch1mneyyy.dsh_app | grep versionCode`；
   `files/runtime/rootfs/opt/dsh-go/direct/proot-dns.mjs` 里应有 `1500`。

---

## 3. 出 APK

Actions → **Build fixed APK** → Run workflow（推 `custom` 也会自动跑；workflow 名在 Actions 列表里可能显示为 **CI**）。

- 产物：release [`strip-fix`](https://github.com/AbelDuan/dsh-Go/releases/tag/strip-fix) 的 `app-arm64-release.apk`，
  以及 run 页的 `dsh-go-arm64-strip-fix` artifact。
- 构建参数：`tooling/build-apk.sh -f arm64 --no-companion --no-star`。
  **`--no-companion`**：仓库里没有授权的桌宠素材包，装出来没有桌宠，其余功能不变。
- 没有 `app/android/key.properties`，所以是 **debug 签名**；手机可以跨签名覆盖安装，数据保留。
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
| 2026-11-24 | `db9452d9` | fork 建立，`custom` = 上游 + 修复提交（`ba37627` 起） |
| 2026-11-24 | `5ac52c368`（75+1 提交，146 个文件） | 合并提交 `a7a3593b8`；冲突 3 处，全在 `pet/PetOverlay.kt`（`onDragEnd` / `onDragCancel` / `askFresh`）：按上游新增的 `dockedRest()` 与 companion float 分支保留双方改动；`EdgeSnap.kt`、`EdgeSnapTest.kt` 三方合并无冲突 |

## 6. 本机补丁备份

`~/.dsh/patches/dsh-go/` 下留了补丁版和说明（`strip-no-crossfade.patch` 等），
上游结构变化导致 merge 特别麻烦时可以直接 `git apply -3`。
