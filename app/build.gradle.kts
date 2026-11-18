plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eband.aligner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eband.aligner"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.webkit:webkit:1.11.0")
}

// Bundle OpenCV.js and Chart.js inside the APK so the app works offline at the radio site.
// They are downloaded once, on the first build, if not already present.
val downloadWebLibs by tasks.registering {
    val outDir = file("src/main/assets/www/lib")
    val libs = mapOf(
        "opencv.js" to "https://docs.opencv.org/4.8.0/opencv.js",
        "chart.umd.min.js" to "https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js"
    )
    outputs.dir(outDir)
    doLast {
        outDir.mkdirs()
        libs.forEach { (name, url) ->
            val f = File(outDir, name)
            if (!f.exists() || f.length() == 0L) {
                logger.lifecycle("Downloading $url")
                java.net.URL(url).openStream().use { input ->
                    f.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadWebLibs) }
