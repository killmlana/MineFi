plugins {
    kotlin("jvm") version "1.9.22"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.minefi"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.20.4-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")

    implementation(kotlin("stdlib"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.web3j:core:4.10.3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.mockk:mockk:1.13.9")
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        relocate("kotlin", "com.minefi.shaded.kotlin")
        relocate("okhttp3", "com.minefi.shaded.okhttp3")
        relocate("okio", "com.minefi.shaded.okio")
        relocate("org.web3j", "com.minefi.shaded.web3j")
        relocate("com.google.zxing", "com.minefi.shaded.zxing")
        relocate("org.bouncycastle", "com.minefi.shaded.bouncycastle")
    }
    test {
        useJUnitPlatform()
    }
    compileKotlin {
        kotlinOptions.jvmTarget = "17"
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}
