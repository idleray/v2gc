plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    application
}

group = "com.v2gc"
version = "1.0-SNAPSHOT"

repositories {
    maven(url = "https://maven.aliyun.com/repository/central")
    maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
    maven(url = "https://maven.aliyun.com/repository/google")
}

dependencies {
    // Ktor
    val ktorVersion = "2.3.7"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-client-auth:$ktorVersion")
    implementation("io.ktor:ktor-client-plugins:$ktorVersion")

    // Git
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.8.0.202311291450-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.jsch:6.8.0.202311291450-r")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Configuration
    implementation("io.github.config4k:config4k:0.6.0")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")

    // JSch for SSH support
    implementation("com.jcraft:jsch:0.1.55")
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

    doFirst {
        // Load test.properties if it exists
        val testPropertiesFile = file("src/test/resources/test.properties")
        if (testPropertiesFile.exists()) {
            testPropertiesFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .forEach { line ->
                    val (key, value) = line.split("=", limit = 2)
                    systemProperty(key.trim(), value.trim())
                }
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
    jvmToolchain(23)
}

application {
    mainClass.set("com.v2gc.ApplicationKt")
}

tasks.jar {
    exclude("application.conf")
    exclude("secrets.conf")
}