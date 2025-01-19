package com.v2gc

import com.typesafe.config.ConfigFactory
import com.v2gc.client.impl.GitClientImpl
import com.v2gc.client.impl.VercelClientImpl
import com.v2gc.model.AppConfig
import com.v2gc.model.GitConfig
import com.v2gc.model.VercelConfig
import io.github.config4k.extract
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) = runBlocking {
    println("V2GC - Vercel to Git Commit")
    println("Starting application...")

    try {
        // Load configuration
        val config = ConfigFactory.load()
        val vercelConfig = config.getConfig("vercel").extract<VercelConfig>()
        val gitConfig = config.getConfig("git").extract<GitConfig>()
        val appConfig = config.getConfig("app").extract<AppConfig>()

        // Initialize HTTP client
        val httpClient = HttpClient(CIO)

        // Initialize Vercel client
        val vercelClient = VercelClientImpl(
            config = vercelConfig,
            httpClient = httpClient,
            retryAttempts = appConfig.retryAttempts,
            retryDelay = appConfig.retryDelay
        )

        // Create temp directory if it doesn't exist
        val tempDir = File(appConfig.tempDir)
        tempDir.mkdirs()

        // Get latest deployments
        println("Fetching latest deployments...")
        val deployments = vercelClient.listDeployments(limit = 1)
        if (deployments.isEmpty()) {
            println("No deployments found")
            exitProcess(1)
        }

        val latestDeployment = deployments.first()
        println("Latest deployment: ${latestDeployment.name} (${latestDeployment.id})")

        // Download source files
        println("Downloading source files...")
        val deploymentDir = File(tempDir, latestDeployment.id)
        vercelClient.downloadSourceFiles(latestDeployment, deploymentDir)
        println("Source files downloaded to ${deploymentDir.absolutePath}")

        // Initialize Git client
        println("Initializing Git repository...")
        val gitClient = GitClientImpl().apply {
            initRepository(deploymentDir)
            setCredentials("oauth2", gitConfig.token)
            setAuthor(gitConfig.authorName, gitConfig.authorEmail)
        }

        // Add all files to Git
        println("Adding files to Git...")
        val files = deploymentDir.walk()
            .filter { it.isFile }
            .filterNot { it.name == ".git" }
            .toList()
        gitClient.addFiles(files)

        // Commit changes
        println("Committing changes...")
        val commitMessage = "Update from Vercel deployment ${latestDeployment.id}\n\n" +
                "Deployment URL: ${latestDeployment.url}\n" +
                "Created at: ${java.time.Instant.ofEpochMilli(latestDeployment.createdAt)}"
        gitClient.commit(commitMessage)

        // Push changes
        println("Pushing changes...")
        gitClient.push(gitConfig.defaultBranch)

        println("Successfully completed Vercel workflow")
        httpClient.close()
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
} 