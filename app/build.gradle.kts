plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    // Keep the source namespace stable; applicationId is the installed/module package.
    namespace = "io.github.xblocker"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.bileizhen.xblocker"
        minSdk = 28
        targetSdk = 36
        versionCode = 9
        versionName = "0.2.6-rc.2"
        // The LSPosed repository requires a no-domain package to use the GitHub username.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Supplied by Xiaomi after the app's Super Island service is enabled.
        resValue("string", "xiaomi_app_id", providers.gradleProperty("xiaomiAppId").orElse("").get())
        manifestPlaceholders["xiaomiDebug"] = "false"
    }
    buildTypes {
        getByName("debug") { manifestPlaceholders["xiaomiDebug"] = "true" }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
dependencies {
    implementation(project(":core")) { exclude(group = "org.json", module = "json") }
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.navigation3:navigation3-runtime:1.1.4")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-navigation3-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:0.9.3")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
