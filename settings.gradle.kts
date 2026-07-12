rootProject.name = "ShardKit"

include("shardkit")

// dev loop: build against the sibling mechanics checkout when present - no publishToMavenLocal,
// no stale-jar-under-a-running-JVM hazards; absent (CI/other machines) the mavenLocal/Central artifact resolves
if (file("../MinestomMechanics").exists()) {
    includeBuild("../MinestomMechanics") {
        dependencySubstitution {
            substitute(module("io.github.term4:minestom-mechanics")).using(project(":"))
        }
    }
}
