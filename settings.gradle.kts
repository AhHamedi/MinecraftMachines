// Portions derived from The Simulated Project build configuration.
// Copyright (c) The Simulated Team / The Creators of Aeronautics.
// Used under the MIT License; see THIRD_PARTY_NOTICES.md.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven("https://repo.spongepowered.org/repository/maven-public")
            }
            filter {
                includeGroupAndSubgroups("org.spongepowered")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "minecraft-machines"

val simulatedProject = file(
    providers.gradleProperty("simulatedProjectDir")
        .orElse("external/Simulated-Project")
        .get()
)

check(simulatedProject.isDirectory) {
    "Simulated-Project checkout not found at ${simulatedProject.absolutePath}. " +
        "Run `git submodule update --init --recursive`, or pass -PsimulatedProjectDir=/path/to/Simulated-Project."
}

include("simulated:common")
include("simulated:neoforge")
project(":simulated").projectDir = simulatedProject.resolve("simulated")
project(":simulated:common").projectDir = simulatedProject.resolve("simulated/common")
project(":simulated:neoforge").projectDir = simulatedProject.resolve("simulated/neoforge")

include("aeronautics:common")
include("aeronautics:neoforge")
project(":aeronautics").projectDir = simulatedProject.resolve("aeronautics")
project(":aeronautics:common").projectDir = simulatedProject.resolve("aeronautics/common")
project(":aeronautics:neoforge").projectDir = simulatedProject.resolve("aeronautics/neoforge")

include("minecraft_machines:common")
include("minecraft_machines:neoforge")
