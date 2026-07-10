plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.36.0"
}

description = "Shard recording and playback - replays as polar files with a registry of typed recordables"
group = "io.github.term4"
version = "0.1.0"
java.toolchain.languageVersion = JavaLanguageVersion.of(25)

mavenPublishing {
    coordinates(group.toString(), "shardkit-replay", version.toString())

    pom {
        name = "shardkit-replay"
        description = project.description
        url = "https://github.com/Term4/ShardKit"

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
            url = "https://github.com/Term4/ShardKit"
            connection = "scm:git:git://github.com/Term4/ShardKit.git"
            developerConnection = "scm:git:ssh://git@github.com/Term4/ShardKit.git"
        }
    }
}

repositories {
    mavenCentral()
    mavenLocal() // local polar fork (dev.hollowcube:polar:dev) until an upstream release targets this Minestom line
    maven("https://jitpack.io")
}

dependencies {
    val minestomVersion = "2026.07.01-26.1.2"
    val junitVersion = "6.0.3"

    api(project(":shardkit"))
    api(project(":shardkit-polar"))
    compileOnly("net.minestom:minestom:$minestomVersion")
    compileOnly("org.slf4j:slf4j-api:2.0.18") // minestom's runtime always carries the binding

    testImplementation("net.minestom:minestom:$minestomVersion")
    testImplementation("org.tinylog:tinylog-api:2.8.0-M1")
    testImplementation("org.tinylog:tinylog-impl:2.8.0-M1")
    testImplementation("org.tinylog:slf4j-tinylog:2.8.0-M1")

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitVersion")
}

tasks.test {
    useJUnitPlatform()
    failOnNoDiscoveredTests = false
    // synchronous player spawns + relaxed thread asserts (like Minestom's Env); must be set before ServerFlag loads
    systemProperty("minestom.inside-test", "true")
    jvmArgs("--enable-native-access=ALL-UNNAMED") // polar's zstd-jni (restricted-method warning on JDK 24+)
}
