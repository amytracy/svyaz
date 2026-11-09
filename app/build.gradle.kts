plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.svyaz.messenger"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.svyaz.messenger"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideload-friendly default: signed with the debug key. Use your own key to distribute.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val goCoreDir = rootProject.layout.projectDirectory.dir("gocore")
val goCoreAar = layout.projectDirectory.file("libs/gocore.aar")

val buildGoCore = tasks.register<Exec>("buildGoCore") {
    description = "Builds the Go network core into app/libs/gocore.aar with gomobile."
    inputs.files(fileTree(goCoreDir) { include("*.go", "go.mod", "go.sum") })
    inputs.file(rootProject.layout.projectDirectory.file("scripts/build-gocore.sh"))
    outputs.file(goCoreAar)
    workingDir = rootProject.projectDir
    commandLine("sh", "scripts/build-gocore.sh")
}

tasks.named("preBuild") { dependsOn(buildGoCore) }

dependencies {
    implementation(files(goCoreAar))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
}
