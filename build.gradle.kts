import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.10"
}

group = "com.kilombino.kilojoin"
version = "0.10.0"

kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// The wallet's own coinjoin and crypto code, from the Kilowallet submodule: one source of truth,
// so a Kilojoin node and the app always speak the same protocol and sign the same way.
// -PkilowalletDir=<a Kilowallet checkout> builds against another copy of it (while changing both).
val kilowallet = (findProperty("kilowalletDir") as String?) ?: "kilowallet"
sourceSets {
    main {
        kotlin {
            srcDir("src/main/kotlin")
            srcDir("$kilowallet/app/src/main/java")
            include("com/kilombino/kilojoin/**")
            include("com/kilombino/pyblockwatch/crypto/**")
            include("com/kilombino/pyblockwatch/ark/ArkNativeStub.kt") // no Ark engine here: Kotlin signing
            include("com/kilombino/pyblockwatch/chain/TorStub.kt") // no Tor here: the relay directly
            include("com/kilombino/pyblockwatch/coinjoin/BlindRsa.kt")
            include("com/kilombino/pyblockwatch/coinjoin/CoinjoinTx.kt")
            include("com/kilombino/pyblockwatch/coinjoin/Nip44.kt")
            include("com/kilombino/pyblockwatch/coinjoin/NostrEvent.kt")
            include("com/kilombino/pyblockwatch/coinjoin/PoolSession.kt")
            include("com/kilombino/pyblockwatch/coinjoin/Protocol.kt")
            include("com/kilombino/pyblockwatch/coinjoin/RelayClient.kt")
        }
        // (src/main/resources is the default resources dir)
    }
    test {
        kotlin {
            srcDir("src/test/kotlin")
            // The app's own coinjoin tests that need nothing of Android: a local relay and the async rounds (Kilojoin v6).
            srcDir("$kilowallet/app/src/test/java")
            include("com/kilombino/kilojoin/**")
            include("com/kilombino/pyblockwatch/coinjoin/TestRelay.kt")
            include("com/kilombino/pyblockwatch/coinjoin/TestChain.kt")
            include("com/kilombino/pyblockwatch/coinjoin/AsyncRoundsTest.kt")
            include("com/kilombino/pyblockwatch/coinjoin/AsyncRoundsE2ETest.kt")
        }
    }
}

dependencies {
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
    // The app's tests are JUnit 4; they run here on the JUnit Platform's vintage engine.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
    // The end-to-end rounds print what each wallet does: shown when a test fails.
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

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
