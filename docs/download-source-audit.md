# 下载量口径审计

共 243 条：A 档可自动 192｜B 档只有分档 12｜C 档拿不到 39。当前有下载数 176 条（72%）。

## 渠道分布

| 档 | 渠道 | 条数 | 精度 | 取法 |
|---|---|--:|---|---|
| A | github-release | 182 | 精确 | Repo Releases API → assets[].download_count |
| C | none | 20 | 无 | 纯源码或无官方渠道 |
| C | official-cdn | 15 | 无 | 自家 CDN，除非项目方暴露统计 |
| B | play-store | 12 | 分档 | 页面文本里的安装量区间（10K+ / 100K+ / 1M+），需浏览器或第三方 API |
| A* | gitea-release | 9 | 精确（逐实例） | GET /api/v1/repos/{o}/{r}/releases → assets[].download_count；值为 0 视为该实例未开统计 |
| C | github-actions | 2 | 无 | Actions artifacts 不计下载 |
| C | fdroid | 1 | 无 | F-Droid 不公开下载计数 |
| A* | gitlab-release | 1 | 精确 | GET /api/v4/projects/{id}/releases → assets.links 无计数，需看 packages |
| C | archive | 1 | 无 | 归档站，链接多已失效 |

## A 档但缺数（可补）

| id | 渠道 | ★ |
|---|--:|--:|
| snes9xexplus | github-release | 1265 |
| dosbox-pure | github-release | 1185 |
| gunacharanteja-winlatormali | github-release | 505 |
| mamcjr-botwrecompandroid | github-release | 493 |
| np2kai | github-release | 438 |
| prosperoeden-ps5 | github-release | 161 |
| mamcjr-totkdroid | github-release | 128 |
| ryubing-emulator | gitea-release | 93 |
| hyperhle | github-release | 85 |
| prospero-eden-encore | github-release | 79 |
| eden-emulator-releases | gitea-release | 38 |
| termux-enhanced | github-release | 24 |
| melange-brew | gitlab-release | 16 |
| eden-sx2a-moceure | gitea-release | 7 |
| maxi000q-consolefivepro | github-release | 5 |
| melonx-ios | gitea-release | 4 |
| 8cee-banjorecomp-android | github-release | 3 |
| eden-ci-nightly | gitea-release | 1 |
| ryubing-canary | gitea-release | 1 |
| kenji-nx-android-releases | gitea-release | 0 |
| ryujinx-benji-sc | gitea-release | 0 |
| yuzu-citrus | gitea-release | 0 |
