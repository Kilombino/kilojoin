import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.10"
}

group = "com.kilombino.kilojoin"
version = "0.3.0"

kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// The wallet's own coinjoin and crypto code, from the Kilowallet submodule: one source of truth,
// so a Kilojoin node and the app always speak the same protocol and sign the same way.
val shared = "kilowallet/app/src/main/java/com/kilombino/pyblockwatch"
sourceSets {
    main {
        kotlin {
            srcDir("src/main/kotlin")
            srcDir("kilowallet/app/src/main/java")
            include("com/kilombino/kilojoin/**")
            include("com/kilombino/pyblockwatch/crypto/**")
            include("com/kilombino/pyblockwatch/ark/ArkNativeStub.kt") // no Ark engine here: Kotlin signing
            include("com/kilombino/pyblockwatch/coinjoin/CoinjoinTx.kt")
            include("com/kilombino/pyblockwatch/coinjoin/Nip44.kt")
            include("com/kilombino/pyblockwatch/coinjoin/NostrEvent.kt")
            include("com/kilombino/pyblockwatch/coinjoin/PoolSession.kt")
            include("com/kilombino/pyblockwatch/coinjoin/Protocol.kt")
            include("com/kilombino/pyblockwatch/coinjoin/RelayClient.kt")
        }
        // (src/main/resources is the default resources dir)
    }
}

dependencies {
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// One self-contained jar for the container.
tasks.register<Jar>("fatJar") {
    archiveFileName.set("kilojoin.jar")
    manifest { attributes["Main-Class"] = "com.kilombino.kilojoin.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    // Reproducible: no timestamps, fixed order.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
