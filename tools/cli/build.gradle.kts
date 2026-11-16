plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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
    implementation(project(":core:apns"))
    implementation(project(":core:bark"))
    implementation(project(":core:net"))
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("io.github.lichon.abark.cli.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
