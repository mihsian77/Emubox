import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 版本号由 CI 注入，本地构建走默认值。
// 规则：versionCode = 主*10_000_000 + 次*100_000 + 修订*1_000 + 末段，
// 末段 1–899 留给预发布（取 CI run number），999 专给正式版，
// 保证「1.4.0 正式版 > 1.4.0 的任一预发布 > 1.3.0」，同段内预发布递增、可覆盖安装。
// 预发布 tag 固定 v1.4.0-pre，构建序号只进 versionName，不进 tag 名。
// 界面截图开关：默认关，本地需要看 UI 时 -PemuboxScreenshots=true
val screenshotEnabled = (project.findProperty("emuboxScreenshots") as String?)?.toBoolean() ?: false

val injectedVersionCode = (project.findProperty("emuboxVersionCode") as String?)?.toIntOrNull()
val injectedVersionName = project.findProperty("emuboxVersionName") as String?

android {
    namespace = "com.emubox.app"
    // 编译目标与 targetSdk 对齐到 Android 17（API 37，2026-06 已正式发布）。
    // 之前是 36.1（Android 16 QPR2）编译、却按 37 声明适配：等于用旧 SDK 备新考纲，
    // 编译期发现不了任何 37 的行为变更问题。大屏强制自适应这类变化只能靠对齐版本暴露。
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.emubox.app"
        minSdk = 28
        targetSdk = 37
        versionCode = injectedVersionCode ?: 10_400_001
        versionName = injectedVersionName ?: "1.4.0-pre"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 仅 arm64-v8a：driverscope 原生库也只有 arm64，现代 Android 设备均为 64 位
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // AdrenoTools 硬性要求：hook .so 必须解压进 nativeLibraryDir 才能被 dlopen。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            // R8：代码压缩 + 混淆（资源收缩已关闭，原因见下方 isShrinkResources 注释）。
            // 开启前实测 dex 45.95MB（占 APK 88%），因为未使用的类、反射元数据、
            // Kotlin 标准库全部原样打包。Compose / Coil / 协程均有官方 consumer 规则，
            // 项目自身无反射式实例化，JNI 方法名由下方 proguard 规则保留。
            isMinifyEnabled = true
            // 资源收缩在 AGP 9.1 + R8 全模式下不可靠：R8 把 R.string 常量内联进调用点后，
            // 收缩器的可达性追踪失效，实测误删 19 个有代码引用的字符串（tab_device /
            // tab_settings / driver_cmp_* 等），真机渲染即抛 Resources$NotFoundException。
            // 瘦身收益全在 isMinifyEnabled（dex 45.95MB → 3.4MB），资源收缩只省约 0.36MB。
            isShrinkResources = false
            // 有 keystore.properties 时用正式签名，否则回退 debug 签名
            signingConfig = run {
                val props = Properties()
                val f = file(System.getProperty("user.home") + "/.emubox/keystore/keystore.properties")
                if (f.exists()) {
                    f.inputStream().use { props.load(it) }
                }
                if (props.containsKey("storeFile")) {
                    signingConfigs.create("release").apply {
                        storeFile = rootProject.file(props.getProperty("storeFile"))
                        storePassword = props.getProperty("storePassword")
                        keyAlias = props.getProperty("keyAlias")
                        keyPassword = props.getProperty("keyPassword")
                    }
                } else {
                    signingConfigs.getByName("debug")
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // Robolectric 需要读取 res/assets 才能构造真实的 Context
        unitTests.isIncludeAndroidResources = true
        // 不要再打开 isReturnDefaultValues：它让 android.jar 里未实现的方法
        // 返回 false / 0 / null，于是「在生产代码里调了只有真机才有的 API」
        // 不会报错、测试静默通过——门禁看着是绿的，实际什么也没验。
        // 关掉后这类调用会抛 "not mocked"，暴露真问题；被测代码该用 Robolectric
        // 就跑 Robolectric，而不是靠这个开关把异常吞掉。
        //
        // 截图默认不录：Roborazzi 只在 -PemuboxScreenshots=true 时把 Compose
        // 画成 PNG（本地看 UI 用）。CI 上跑 166 个用例，其中 5 个是纯截图任务，
        // 每次渲染 + 等数据到位要 2.5 秒以上，产物却没人看。
        unitTests.all {
            it.systemProperty("emubox.screenshots", screenshotEnabled.toString())
            it.systemProperty("roborazzi.test.record", screenshotEnabled.toString())
            it.systemProperty("roborazzi.test.verify", "false")
        }
    }
    lint {
        // 本项目为多语言但不保证全覆盖的社区项目，MissingTranslation 由
        // scripts/check-i18n.py 自定义脚本保证中英一致性，此处关闭 Lint 检查
        disable += "MissingTranslation"
        // 允许警告不阻断构建
        abortOnError = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    // 在 JVM 上模拟 Android 环境（Context / Uri / ContentResolver），无需真机
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
    // Compose 界面在电脑上真实渲染成图片（无需模拟器/真机）
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.okhttp)
    implementation(libs.org.json)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.androidx.documentfile)
    // 自研驱动渲染实测内核（私有仓 emubox-drivercheck 构建的 AAR，闭源分发）
    implementation(files("libs/drivercheck.aar"))
}