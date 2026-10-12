plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 以 JDK 17 toolchain 編譯（Java 與 Kotlin 同步 17），不受執行 Gradle 的 JDK 版本影響
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":libautomata"))
    testImplementation(libs.junit)
}
