val springGrpcVersion = providers.gradleProperty("springGrpcVersion").get()
val protobufVersion = providers.gradleProperty("protobufVersion").get()
val grpcVersion = providers.gradleProperty("grpcVersion").get()
val protobufGradlePluginVersion = providers.gradleProperty("protobufGradlePluginVersion").get()

plugins {
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("com.google.protobuf") version "0.10.0"
}

dependencies {
    implementation(platform("org.springframework.grpc:spring-grpc-dependencies:$springGrpcVersion"))
    implementation(project(":sales-domain"))
    implementation("org.springframework.grpc:spring-grpc-server-spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")
}

sourceSets {
    main {
        proto {
            srcDir("../proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}
