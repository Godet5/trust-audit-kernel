import java.io.File

plugins {
    kotlin("jvm") version "2.1.10"
}

group = "com.aegisone"
version = "0.1.0-phase0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.47.0.0")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

// On Android/Termux, /tmp is outside the linker namespace trusted by the JVM.
// sqlite-jdbc extracts its native lib to /tmp by default, causing dlopen to fail.
// When running on Termux, point sqlite-jdbc at a pre-extracted copy in harness/native/.
// On standard JVMs (Linux x86_64, macOS, Windows), sqlite-jdbc uses its own bundled native.
val isTermux: Boolean = System.getenv("TERMUX_VERSION") != null ||
    File("/data/data/com.termux").exists()

tasks.test {
    useJUnitPlatform()
    if (isTermux) {
        jvmArgs(
            "-Dorg.sqlite.lib.path=${projectDir}/native",
            "-Dorg.sqlite.lib.name=libsqlitejdbc.so"
        )
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

kotlin {
    jvmToolchain(21)
}
