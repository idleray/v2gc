package com.v2gc

import com.typesafe.config.ConfigFactory
import com.v2gc.client.impl.GitHubClientImpl
import com.v2gc.client.impl.VercelClientImpl
import com.v2gc.model.AppConfig
import com.v2gc.model.GitHubConfig
import com.v2gc.model.VercelConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: java -jar app.jar <application.conf> <secrets.conf>")
        return
    }

    val applicationConfig = File(args[0])
    val secretsConfig = File(args[1])
    
    if (!applicationConfig.exists() || !secretsConfig.exists()) {
        println("Configuration files not found!")
        return
    }

    val config = ConfigFactory.parseFile(secretsConfig)
        .withFallback(ConfigFactory.parseFile(applicationConfig))
        .resolve()
    
    val app = Application(config)
    app.start()
}

class Application(private val config: com.typesafe.config.Config) {
    fun start() = runBlocking {
        try {
            // Load configuration
            val vercelConfig = VercelConfig(
                apiUrl = "https://api.vercel.com",
                token = config.getString("vercel.token"),
                teamId = config.getString("vercel.teamId"),
                projectName = config.getString("vercel.projectName")
            )
            val appConfig = AppConfig(
                projectRootDir = config.getString("app.projectRootDir"),
                retryAttempts = config.getInt("app.retryAttempts"),
                retryDelay = config.getLong("app.retryDelay")
            )

            val githubConfig = GitHubConfig(
                token = config.getString("github.token"),
                owner = config.getString("github.owner"),
                repo = vercelConfig.projectName
            )

            // Initialize HTTP client
            val httpClient = HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        coerceInputValues = true
                        prettyPrint = true
                        isLenient = true
                    })
                }
                install(Logging) {
                    level = LogLevel.INFO
                }
                engine {
                    requestTimeout = 60.seconds.inWholeMilliseconds
                }
            }

            // Initialize Vercel client
            val vercelClient = VercelClientImpl(vercelConfig, httpClient)

            // Initialize GitHub client
            val githubClient = GitHubClientImpl(githubConfig)

            // Fetch latest deployment
            println("Fetching latest deployment...")
            val deployments = vercelClient.listDeployments(limit = 1)
            if (deployments.isEmpty()) {
                throw RuntimeException("No deployments found")
            }
            val latestDeployment = deployments.first()
            println("Latest deployment: ${latestDeployment.id} (${latestDeployment.url})")

            // Prepare directories
            val vercelDir = File(appConfig.projectRootDir, vercelConfig.projectName)
            val tempDir = File(System.getProperty("java.io.tmpdir"), 
                "vercel/${vercelConfig.projectName}/${latestDeployment.id}")

            // Check directory and repository status
            val vercelDirExists = vercelDir.exists()
            val repoExists = githubClient.repositoryExists()

            println("Vercel directory exists: $vercelDirExists")
            println("GitHub repository exists: $repoExists")

            // Handle directory setup based on conditions
            when {
                !vercelDirExists && !repoExists -> {
                    println("Creating new GitHub repository and cloning to $vercelDir")
                    githubClient.createRepository()
                    githubClient.cloneRepository(vercelDir)
                }
                !vercelDirExists && repoExists -> {
                    println("Cloning existing GitHub repository to $vercelDir")
                    githubClient.cloneRepository(vercelDir)
                }
                vercelDirExists && !repoExists -> {
                    println("Deleting existing vercelDir and creating new GitHub repository")
                    vercelDir.deleteRecursively()
                    githubClient.createRepository()
                    githubClient.cloneRepository(vercelDir)
                }
                vercelDirExists && repoExists -> {
                    println("Cleaning src directory in existing vercelDir")
                    File(vercelDir, "src").deleteRecursively()
                }
            }

            // Download Vercel files to temp directory
            println("Downloading source files...")
            vercelClient.downloadSourceFiles(latestDeployment, tempDir)

            // Copy files from temp to vercel directory
            println("Copying files to $vercelDir")
            tempDir.copyRecursively(vercelDir, overwrite = true)
            tempDir.deleteRecursively()

            println("Source files processed in ${vercelDir.absolutePath}")

            // Commit and push changes
            println("Committing and pushing changes...")
            githubClient.commitAndPush(vercelDir, latestDeployment.id)

            // Cleanup
            httpClient.close()
            println("Done!")

        } catch (e: Exception) {
            println("Error: ${e.message}")
            e.printStackTrace()
            System.exit(1)
        }
    }
}