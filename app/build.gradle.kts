import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// v55 (kovennus): julkaisuallekirjoitus luetaan projektin juuressa olevasta
// keystore.properties-tiedostosta, JOS se on olemassa. Näin salaista
// allekirjoitusavainta ei koskaan tallenneta lähdekoodiin eikä jaettuun zipiin,
// eikä nykyinen työnkulku rikkoudu jos tiedostoa ei vielä ole (silloin release
// jää allekirjoittamatta ja sen voi allekirjoittaa Android Studion
// "Generate Signed APK/Bundle" -velholla). Katso JAKELU_JA_KOVENNUS.md.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
val hasReleaseKeystore =
    keystorePropsFile.exists() && !keystoreProps.getProperty("storeFile").isNullOrBlank()

android {
    namespace = "fi.kasvila.reko"
    compileSdk = 34

    defaultConfig {
        applicationId = "fi.kasvila.reko"
        minSdk = 26
        targetSdk = 34
        // v40+: the app shows this same versionName in the UI via BuildConfig,
        // so the version number lives in ONE place — update it only here.
        versionCode = 58
        versionName = "58"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // v55: kovennus. R8 kutistaa, optimoi ja OBFUSKOI koodin — tämä on
            // se osa joka tekee sovelluksesta selvästi vaikeamman muokata/murtaa.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true   // generates BuildConfig.VERSION_NAME / VERSION_CODE
    }
}

dependencies {
    // v58: Ostot ja varasto -moduuli (ostot/).
    implementation(project(":ostot"))
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // v46: Bluetooth thermal receipt printing (ESC/POS) + QR-code generation.
    implementation("com.github.DantSu:ESCPOS-ThermalPrinter-Android:3.3.0")
    implementation("com.google.zxing:core:3.5.3")
    // v48: Argon2id password hashing / key derivation (bundled native libs).
    implementation("com.lambdapioneer.argon2kt:argon2kt:1.6.0")
    // v52: biometric unlock (fingerprint / face).
    implementation("androidx.biometric:biometric:1.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
