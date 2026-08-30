plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

description = "Isolated gameplay worlds over shared Minestom instances - diffs, views, per-shard ticking, polar saves, replays"
group = "io.github.term4"
version = "0.2.0"
java.toolchain.languageVersion = JavaLanguageVersion.of(25)

mavenPublishing {
    coordinates(group.toString(), "archipelago", version.toString())

    pom {
        name = "archipelago"
        description = project.description
        url = "https://github.com/Term4/Archipelago"

        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
                distribution = "repo"
            }
        }

        developers {
            developer {
                name = "Term4"
                id = "Term4"
                email = "gptkc2003@gmail.com"
                url = "https://github.com/Term4"
            }
        }

        scm {
            url = "https://github.com/Term4/Archipelago"
            connection = "scm:git:git://github.com/Term4/Archipelago.git"
            developerConnection = "scm:git:ssh://git@github.com/Term4/Archipelago.git"
        }
    }
}

repositories {
    mavenLocal() // polyp-world when the sibling checkout is absent
    mavenCentral()
}

dependencies {
    val minestomVersion = "2026.08.28-26.2"
    val junitVersion = "6.1.3"

    // the world seam is PROVIDED at runtime (by polyp, which embeds it, or an explicit polyp-world dep for
    // archipelago-without-mechanics) - the same contract as minestom below; so the published POM carries no deps
    compileOnly("io.github.term4:polyp-world:0.3.1")
    testImplementation("io.github.term4:polyp-world:0.3.1")
    compileOnly("net.minestom:minestom:$minestomVersion")
    compileOnly("org.slf4j:slf4j-api:2.0.18") // minestom's runtime always carries the binding
    // optional: only the polar/replay packages touch it - add polar to your app to use saves/replays
    compileOnly("dev.hollowcube:polar:1.16.0")

    testImplementation("net.minestom:minestom:$minestomVersion")
    testImplementation("dev.hollowcube:polar:1.16.0")
    testImplementation("com.github.luben:zstd-jni:1.5.7-16") // storage harnesses compress candidate payloads
    testImplementation("org.tinylog:tinylog-api:2.8.0-M2")
    testImplementation("org.tinylog:tinylog-impl:2.8.0-M2")
    testImplementation("org.tinylog:slf4j-tinylog:2.8.0-M2")

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitVersion")
}

tasks.test {
    useJUnitPlatform()
    // synchronous player spawns + relaxed thread asserts (like Minestom's Env); must be set before ServerFlag loads
    systemProperty("minestom.inside-test", "true")
    systemProperty("breakdown.files", System.getProperty("breakdown.files", "")) // extra replay files for the harness
    // forward the full-soak knob into the forked test JVM (CLI -D alone never reaches it)
    System.getProperty("archipelago.soak.ms")?.let { systemProperty("archipelago.soak.ms", it) }
    jvmArgs("--enable-native-access=ALL-UNNAMED") // polar's zstd-jni (restricted-method warning on JDK 24+)
}

tasks.register<JavaExec>("runTestServer") {
    group = "application"
    description = "Runs the Archipelago example server (offline mode, port 25565); the combined mechanics playground lives in the Tennacle repo"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "test.TestServer"
    jvmArgs("--enable-native-access=ALL-UNNAMED") // polar's zstd-jni
}
