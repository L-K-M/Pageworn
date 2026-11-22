import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries)
}

// Release identity comes from the version tag (see .github/workflows/release.yml).
// A tag vMAJOR.MINOR.PATCH gives the version name MAJOR.MINOR.PATCH and the
// version code MAJOR * 1_000_000 + MINOR * 1_000 + PATCH, which rises with every release.
val pagewornVersionName: String = providers.gradleProperty("pageworn.versionName").orElse("0.0.0-dev").get()
val pagewornVersionCode: Int = run {
    val parts = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(pagewornVersionName)?.groupValues
    val code = if (parts == null) 0 else parts[1].toInt() * 1_000_000 + parts[2].toInt() * 1_000 + parts[3].toInt()
    code.coerceAtLeast(1)
}

// The four signing inputs. CI passes them as environment variables decoded from
// repository secrets. They never enter the repository, a log or a build artifact.
val signingInputNames = listOf(
    "PAGEWORN_KEYSTORE_FILE",
    "PAGEWORN_KEYSTORE_PASSWORD",
    "PAGEWORN_KEY_ALIAS",
    "PAGEWORN_KEY_PASSWORD",
)
val signingInputs: Map<String, String?> = signingInputNames.associateWith { name ->
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
}

android {
    namespace = "ch.lkmc.pageworn"
    compileSdk = 37

    defaultConfig {
        applicationId = "ch.lkmc.pageworn"
        minSdk = 31
        targetSdk = 37
        versionCode = pagewornVersionCode
        versionName = pagewornVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingInputs.values.all { it != null }) {
            create("release") {
                storeFile = file(signingInputs.getValue("PAGEWORN_KEYSTORE_FILE")!!)
                storePassword = signingInputs.getValue("PAGEWORN_KEYSTORE_PASSWORD")
                keyAlias = signingInputs.getValue("PAGEWORN_KEY_ALIAS")
                keyPassword = signingInputs.getValue("PAGEWORN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // Generated audio fixtures (fixtures/generate.py) are never committed.
        // Instrumented tests read them from the test APK's assets when present.
        getByName("androidTest").assets.directories.add(rootProject.file("fixtures/out/assets").path)
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The notices list the release variant's runtime dependencies. Platform/BOM entries
// carry no shipped code, so they stay out of the list.
aboutLibraries {
    collect {
        includePlatform = false
    }
    export {
        prettyPrint = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

// Fails a release build that lacks a signing input, naming the input and never its value.
val verifyReleaseSigning by tasks.registering {
    val missing = signingInputs.filterValues { it == null }.keys.toList()
    val keystore = signingInputs["PAGEWORN_KEYSTORE_FILE"]
    doLast {
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing input missing: ${missing.joinToString(", ")}. " +
                    "Set it as an environment variable (the release workflow decodes it from the repository secret of the same name).",
            )
        }
        if (keystore != null && !File(keystore).isFile) {
            throw GradleException("Release signing input PAGEWORN_KEYSTORE_FILE does not name a readable keystore file.")
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseSigning) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.room.testing)
}
