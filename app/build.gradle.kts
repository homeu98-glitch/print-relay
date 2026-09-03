plugins {
    id("com.android.application")
}

android {
    namespace = "com.macau.printhub"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.macau.printhub"
        // 純轉接 Hub：裝喺店內 PC/Android 盒仔，掃 LAN 印表機 + 收雲端中繼列印請求。
        minSdk = 24
        targetSdk = 36
        // v1.1.3：補「失敗原因要睇得到」——
        // ① RelayState 加 lastPrintError，JobRunner / HTTP 8787 失敗時寫低原因
        // ② 常駐通知帶埋原因 + BigTextStyle（中繼專用機 headless 行，通知係唯一會俾人睇到嘅嘢）
        versionCode = 5
        versionName = "1.1.3"

        // macau-pos 線上版（Vercel）——配對時向佢拎 supabaseUrl / anonKey / storeId。
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
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.zxing:core:3.5.3")
    // 廠商 ESC/POS SDK：渲染 + LAN TCP 連線統一經 net.posprinter（同 print-agent-android）。
    implementation(files("libs/printer-lib-3.5.3.aar"))
    // 雲端中繼：Supabase Realtime（Phoenix over WSS）+ Vercel REST（claim/result/heartbeat）。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 本機 HTTP 服務：畀 macau-pos 直連區網嘅 Hub 打 POST /print（NanoHTTPD 純 Java，minSdk 無限制）。
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
