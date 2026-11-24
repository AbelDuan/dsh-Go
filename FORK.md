# dsh-Go · AbelDuan fork

上游：<https://github.com/ccch1mneyyy/dsh-Go>

## 分支

| 分支 | 内容 |
| --- | --- |
| `main` | 上游镜像，只同步，不放任何本地修改 |
| `custom` | 默认分支 = `main` + 本 fork 的修复提交 |

这样上游更新时只需要 `git merge upstream/main`，修复作为普通提交跟着走，不用每次重新打补丁。

## 修复了什么

1. **输入框上方状态条的灰闪**（2026-11-24）
   `app/lib/ui/plugins/native/composer_strip.dart`
   多个插件行轮换（上下文 / API 额度）时，外层 `AnimatedSwitcher` 把旧的一行
   留在树里淡出、新的一行叠在上面淡入。两行各自带一层玻璃底，交叉淡化期间两层
   玻璃叠在同一位置，横条上部约 1/3 会发灰闪一下。
   改为直接换行；同一插件自己文字变化的 160ms 淡入仍保留（内层 `AnimatedSwitcher`）。

## 上游更新后怎么合

1. 仓库首页 **Sync fork** → 把 `main` 同步到上游最新。
2. Actions → **Sync upstream into custom** → Run workflow（每天 03:23 也会自动跑一次）。
   它把 `main` 合进 `custom`，没有冲突就直接推回去。
3. 有冲突时任务会失败、`custom` 不动，本地解决：

```sh
git clone -b custom git@github.com:AbelDuan/dsh-Go.git
cd dsh-Go
git remote add upstream https://github.com/ccch1mneyyy/dsh-Go.git
git fetch upstream && git merge upstream/main
# 解决冲突后
git push origin custom
```

## 出 APK

Actions → **Build fixed APK** → Run workflow（推 `custom` 也会自动跑）。
产物：run 页面的 `dsh-go-arm64-strip-fix` artifact，以及 release
[`strip-fix`](https://github.com/AbelDuan/dsh-Go/releases/tag/strip-fix) 里的
`app-arm64-release.apk`。

- 本仓库没有 `app/android/key.properties`，所以是 debug 签名；手机可以跨签名覆盖安装，数据保留。
- 构建用 `--no-companion`：仓库里没有授权的桌宠素材包，装出来没有桌宠，其余功能不变。

## 本机补丁（备用）

`~/.dsh/patches/dsh-go/strip-no-crossfade.patch` 是同一处修改的补丁版，
上游结构变化导致 merge 麻烦时可以直接 `git apply -3`。
