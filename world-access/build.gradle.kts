plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.36.0"
}

description = "A narrow gameplay-world abstraction over Minestom instances"
group = "io.github.term4"
version = "0.1.0"
java.toolchain.languageVersion = JavaLanguageVersion.of(25)

mavenPublishing {
    coordinates(group.toString(), "world-access", version.toString())

    pom {
        name = "world-access"
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
    maven("https://jitpack.io")
}

dependencies {
    val minestomVersion = "2026.07.01-26.1.2"
    compileOnly("net.minestom:minestom:$minestomVersion")
}
