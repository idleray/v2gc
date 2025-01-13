plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    application
}

group = "com.v2gc"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor
    val ktorVersion = "2.3.7"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // Git
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.8.0.202311291450-r")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Configuration
    implementation("io.github.config4k:config4k:0.6.0")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.8")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Create a separate task for integration tests
val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests."
    group = "verification"

    useJUnitPlatform {
        includeTags("integration")
    }

    // Load test.properties if it exists
    val testPropertiesFile = file("src/test/resources/test.properties")
    if (testPropertiesFile.exists()) {
        val properties = java.util.Properties()
        properties.load(testPropertiesFile.inputStream())
        properties.forEach { (key, value) ->
            systemProperty(key.toString(), value.toString())
        }
    }

    // Always run after unit tests
    shouldRunAfter(tasks.test)
}

// Add integration tests to check task
tasks.check {
    dependsOn(integrationTest)
}

kotlin {
    jvmToolchain(11)
}

application {
    mainClass.set("com.v2gc.ApplicationKt")
}