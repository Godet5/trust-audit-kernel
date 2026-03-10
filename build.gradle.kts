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

tasks.test {
    useJUnitPlatform()
    // On Android/Termux, /tmp is outside the linker namespace trusted by the
    // Termux JVM. sqlite-jdbc extracts its native lib there by default, causing
    // dlopen to fail. Point it at a pre-extracted copy in harness/native/ which
    // is within the Termux data directory namespace.
    jvmArgs(
        "-Dorg.sqlite.lib.path=${projectDir}/native",
        "-Dorg.sqlite.lib.name=libsqlitejdbc.so"
    )
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

kotlin {
    jvmToolchain(21)
}
