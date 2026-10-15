import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

group = "app.localizeme"
version = "1.0.0"

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

}

kotlin {
    // Built with Kotlin 2.2, but apps still on Kotlin 2.0 must be able to read
    // the classes: their compiler rejects metadata newer than 2.1, and the
    // stdlib this depends on would pull them up to 2.2 as well.
    coreLibrariesVersion = "2.0.0"
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
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

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates("app.localizeme", "sdk", version.toString())

    // Maven Central requires every artifact to be signed. A build without a
    // key (a local publish, or JitPack building a tag) publishes unsigned.
    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("LocalizeMe Android SDK")
        description.set(
            "Over-the-air translations for Android apps from LocalizeMe: approved strings " +
                "reach the app on its next start without a Play Store release.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/localizeme-app/localizeme-android-sdk")
        licenses {
            license {
                name.set("PolyForm Shield License 1.0.0")
                url.set("https://polyformproject.org/licenses/shield/1.0.0")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("localizeme-app")
                name.set("LocalizeMe")
                url.set("https://localizeme.app")
            }
        }
        scm {
            url.set("https://github.com/localizeme-app/localizeme-android-sdk")
            connection.set("scm:git:git://github.com/localizeme-app/localizeme-android-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/localizeme-app/localizeme-android-sdk.git")
        }
    }
}
