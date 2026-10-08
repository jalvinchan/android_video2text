import java.util.Properties
import java.util.Base64
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Deployment values live in untracked local.properties or Gradle properties.
val deployment = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun deploymentValue(name: String) = (providers.gradleProperty(name).orNull ?: deployment.getProperty(name, "")).trim()
val billingPublicKey = deploymentValue("playBillingPublicKey")
val adsAppId = deploymentValue("admobAppId")
val bannerAdId = deploymentValue("admobBannerAdUnitId")
val monetizationEnabled = billingPublicKey.isNotBlank() && adsAppId.isNotBlank() && bannerAdId.isNotBlank()
if (monetizationEnabled) {
    require(Regex("ca-app-pub-[0-9]{16}~[0-9]{10}").matches(adsAppId)) { "Invalid AdMob app ID." }
    require(Regex("ca-app-pub-[0-9]{16}/[0-9]{10}").matches(bannerAdId)) { "Invalid AdMob banner ID." }
    require(runCatching {
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(billingPublicKey)))
    }.isSuccess) { "The Play billing public key must be a Base64-encoded RSA public key." }
}
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

android {
    namespace = "com.example.video2text"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.video2text"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("boolean", "MONETIZATION_ENABLED", monetizationEnabled.toString())
        buildConfigField("String", "PLAY_BILLING_PUBLIC_KEY", quoted(billingPublicKey))
        // Google's sample app ID keeps unconfigured builds runnable; no live ad requests.
        manifestPlaceholders["admobAppId"] = adsAppId.ifBlank { "ca-app-pub-3940256099942544~3347511713" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                targets += "transcription"
            }
        }
    }

    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "BANNER_AD_UNIT_ID", quoted("ca-app-pub-3940256099942544/6300978111"))
        }
        release {
            buildConfigField("String", "BANNER_AD_UNIT_ID", quoted(bannerAdId))
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.play.billing)
    implementation(libs.google.mobile.ads)
    implementation(libs.google.ump)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
