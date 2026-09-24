plugins {
    id("com.android.library") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    // 0.35 is the last release that runs on Gradle 8; 0.36 and later need Gradle 9.
    id("com.vanniktech.maven.publish") version "0.35.0" apply false
}
