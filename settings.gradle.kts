pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "grpc-x-rest-benchmark"

include(
    "sales-domain",
    "sales-service-rest",
    "sales-service-grpc",
    "datagen"
)
