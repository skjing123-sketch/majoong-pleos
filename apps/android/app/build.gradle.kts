plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val webAssets = layout.buildDirectory.dir("generated/webAssets")
val syncWebAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../../web"))
    into(webAssets.map { it.dir("web") })
    exclude("node_modules/**", ".env*", "**/*.map")
}

android {
    namespace = "ai.nearby.reunion"
    compileSdk = 35
    // Official Android Automotive API. Not packaged or replaced by a fake implementation.
    useLibrary("android.car", false)
    defaultConfig {
        applicationId = "ai.nearby.reunion"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        // Test-only telemetry; normal builds leave this disabled.
        buildConfigField("boolean", "VEHICLE_SAMPLE_AUDIT",
            (providers.gradleProperty("majoong.vehicleSampleAudit").orNull == "true").toString())
        buildConfigField("String", "WEB_URL", "\"http://10.0.2.2:8787/?platform=android\"")
    }
    flavorDimensions += "vehicle"
    productFlavors {
        create("demo") {
            dimension = "vehicle"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("String", "PLATFORM_MODE", "\"demo\"")
            manifestPlaceholders["appLabel"] = "마중 · 체험"
        }
        create("pleos") {
            dimension = "vehicle"
            versionNameSuffix = "-pleos"
            buildConfigField("String", "PLATFORM_MODE", "\"pleos\"")
            buildConfigField("String", "WEB_URL", "\"http://10.0.2.2:8787/?platform=android&dataMode=live\"")
            manifestPlaceholders["appLabel"] = "마중 · Pleos"
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir(webAssets)
}

tasks.named("preBuild").configure { dependsOn(syncWebAssets) }

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    "pleosImplementation"("ai.pleos.playground:NaviHelper:3.0.5")
    testImplementation("junit:junit:4.13.2")
}
