plugins {
    id("com.android.application")
}

android {
    namespace = "com.macau.pos.printagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.macau.pos.printagent"
        // docs/96：minSdk 26 → 24，等 Sunmi V2（Android 7.1 = API 25）裝到。
        // 現有 deps 最低要求：printer-lib-3.5.3.aar = 21、com.sunmi:printerlibrary = 19、
        // okhttp 4.12 = 21、androidx.activity/lifecycle = 21 → 24 全部安全。
        minSdk = 24
        targetSdk = 36
        // docs/96：雲端中繼改為「Android 自註冊配對」（手動輸入店舖 ID，取代掃 QR）
        // → 配對流程改變，必須 bump（source 改完唔等於生效：要 rebuild APK + 重新派版）
        // 2026-09-02：修 SdkPrinter.connect() 無 timeout 永久掛起（同 Print Hub v1.1.2 同一個 bug）
        // + LAN 打印機改行 raw socket 優先（5s timeout），失敗先 fallback 廠商 SDK。
        // v1.1.3：補「失敗原因要睇得到」——① 雲端中繼失敗寫低原因 + Activity 紅字顯示
        // ② 常駐通知帶埋原因 + BigTextStyle（headless 中繼專用機唯一會俾人睇到嘅嘢）
        // ③ 8787 打印頁失敗時唔好自動閂（700ms 根本睇唔到寫乜）
        versionCode = 8
        versionName = "1.1.3"

        buildConfigField("String", "POS_URL", "\"https://macau-pos-system.vercel.app\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.zxing:core:3.5.3")
    // 廠商 ESC/POS SDK（docs/75 路(b)）：渲染 + 連接統一經 net.posprinter，
    // 順便消滅大字體變扁（行距）同中文唔變大（Kanji）兩個 raw 字節 bug。
    implementation(files("libs/printer-lib-3.5.3.aar"))

    // docs/96 §8：Sunmi 內置打印機（AIDL）。純 Java 包裝、minSdk 19、無 JNI →
    // 同 arm-v7a 嘅 Sunmi V2 無衝突。targetSdk ≥ 30 必須喺 Manifest 加 <queries>
    // 宣告 woyou.aidlservice.jiuiv5.IWoyouService，否則 bindService 靜默失敗。
    implementation("com.sunmi:printerlibrary:1.0.24")

    // docs/96 §7：雲端中繼 —— Supabase Realtime（Phoenix over WSS）+ Vercel REST。
    // OkHttp 4.12 最低 API 21，同 minSdk 24 相容。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
