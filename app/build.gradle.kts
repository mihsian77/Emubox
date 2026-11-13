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
val injectedVersionCode = (project.findProperty("emuboxVersionCode") as String?)?.toIntOrNull()
val injectedVersionName = project.findProperty("emuboxVersionName") as String?

android {
    namespace = "com.emubox.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
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
            isMinifyEnabled = false
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
        unitTests.isReturnDefaultValues = true
        // 界面截图录制开关：让 Roborazzi 在 JVM 上把 Compose 画成 PNG
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
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
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.2.1")
    // Compose 界面在电脑上真实渲染成图片（无需模拟器/真机）
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.json:json:20260522")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation(libs.coil.compose)
    implementation("androidx.documentfile:documentfile:1.1.0")
    // 自研驱动渲染实测内核（私有仓 emubox-drivercheck 构建的 AAR，闭源分发）
    implementation(files("libs/drivercheck.aar"))
}