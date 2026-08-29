rootProject.name = "Archipelago"

include("archipelago")

// dev loop: build against the sibling Polyp checkout when present; absent, mavenLocal/Central resolves
if (file("../Polyp").exists()) {
    includeBuild("../Polyp") {
        dependencySubstitution {
            substitute(module("io.github.term4:polyp-world")).using(project(":world"))
        }
    }
}
