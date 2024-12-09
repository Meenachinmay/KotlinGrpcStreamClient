import java.util.random.RandomGeneratorFactory.all

plugins {
    kotlin("jvm") version "2.0.0"
    id("com.google.protobuf") version "0.9.4"
}

group = "org.polarmeet"
version = "1.0-SNAPSHOT"

// Define versions in one place for better maintenance
val grpcVersion = "1.60.0"
val grpcKotlinVersion = "1.4.1"
val protobufVersion = "3.25.1"
val coroutinesVersion = "1.7.3"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.grpc:grpc-netty-shaded:1.58.0")  // Use shaded Netty
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("com.google.protobuf:protobuf-kotlin:$protobufVersion")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation("org.slf4j:slf4j-simple:2.0.7")      // For logging

    testImplementation(kotlin("test"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("grpc")
                create("grpckt")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}