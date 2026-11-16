plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":core:net"))
    implementation(libs.kotlinx.serialization.json)

    // Activation: PKCS#10 CSR construction, PEM/PKCS#1 parsing. BouncyCastle's own provider is *not*
    // installed — JCA resolves "SHA256withRSA"/"SHA1withRSA" from the platform, which keeps this module
    // Android-safe (Android bundles its own repackaged BC under com.android.org.bouncycastle).
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
