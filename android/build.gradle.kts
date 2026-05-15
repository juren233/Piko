import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseSigningProperties = Properties()
val releaseSigningPropertiesFile = rootProject.file("android/signing/release-signing.properties")
if (releaseSigningPropertiesFile.isFile) {
    releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
}

fun signingValue(propertyName: String, environmentName: String): String? =
    releaseSigningProperties.getProperty(propertyName)
        ?: System.getenv(environmentName)

val releaseStoreFile = signingValue("storeFile", "ANDROID_KEYSTORE_PATH")
val releaseStorePassword = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "ANDROID_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "ANDROID_KEY_PASSWORD")
val androidAbis = providers.gradleProperty("pikoAndroidAbis")
    .orElse("arm64-v8a")
    .get()
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
val pikoVersionName = providers.gradleProperty("piko.versionName").get()
val pikoVersionCode = providers.gradleProperty("piko.versionCode").get().toInt()
val hasReleaseSigning =
    !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

val pikoApiBaseUrl = providers.gradleProperty("piko.apiBaseUrl")
    .orElse("https://piko-api.juren233.top")
    .get()

android {
    namespace = "com.juren233.piko"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.juren233.piko"
        minSdk = 33
        targetSdk = 36
        versionCode = pikoVersionCode
        versionName = pikoVersionName

        buildConfigField("String", "PIKO_API_BASE_URL", "\"$pikoApiBaseUrl\"")

        ndk {
            abiFilters += androidAbis
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DPIKO_XQUIC_GIT_TAG=v1.9.2",
                    "-DPIKO_BORINGSSL_GIT_TAG=main",
                )
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.backdrop)
    implementation(libs.bcprov)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kyant.shapes)
    implementation(libs.okhttp)
    implementation(libs.org.json)
    implementation(libs.webrtc.android)
    testImplementation(libs.org.json)
    testImplementation(kotlin("test-junit"))
}
