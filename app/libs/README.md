# app/libs

`drivercheck.aar` 是自研驱动渲染实测内核（私有仓 `emubox-drivercheck` 构建），
**不提交到本仓库**——EmuBox 是公开仓库，放入二进制等于分发闭源产物。

## 本地构建

```bash
# 方式一：从私有仓下载最近一次 CI 产物
gh run download -R mihsian77/emubox-drivercheck -n drivercheck-aar -D /tmp/dcaar
cp /tmp/dcaar/lib-release.aar app/libs/drivercheck.aar

# 方式二：私有仓本地构建
cd ../drivercheck && ./gradlew :lib:assembleRelease
cp lib/build/outputs/aar/lib-release.aar ../Emubox/app/libs/drivercheck.aar
```

## CI 构建

GitHub Actions 通过 `secrets.DRIVERCHECK_TOKEN`（只读该私有仓）从私有仓
release `aar-latest` 下载。缺失该 secret 时构建会明确报错，不会静默跳过。
