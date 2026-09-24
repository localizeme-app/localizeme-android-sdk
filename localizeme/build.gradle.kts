plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group = "app.localizeme"
version = "0.1.0-beta.1"

android {
    namespace = "app.localizeme.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$version\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Foreground detection. Nearly every app already has this.
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    testImplementation("junit:junit:4.13.2")
    // android.jar stubs org.json for JVM tests; the real one makes them run.
    testImplementation("org.json:json:20250107")

    // Instrumented tests: an AppCompat activity with an XML layout, run with
    // `./gradlew :localizeme:connectedDebugAndroidTest` on an emulator.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.appcompat:appcompat:1.7.0")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "app.localizeme"
            artifactId = "sdk"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
        }
    }
}
