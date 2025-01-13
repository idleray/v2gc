package com.v2gc.client.impl

import com.v2gc.client.VercelClient
import com.v2gc.client.exception.VercelApiException
import com.v2gc.client.exception.VercelAuthenticationException
import com.v2gc.client.exception.VercelFileDownloadException
import com.v2gc.model.VercelConfig
import com.v2gc.model.VercelDeployment
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds

class VercelClientImpl(
    private val config: VercelConfig,
    private val retryAttempts: Int = 3,
    private val retryDelay: Long = 1000
) : VercelClient {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
        install(Logging) {
            level = LogLevel.INFO
        }
        defaultRequest {
            header("Authorization", "Bearer ${config.token}")
            contentType(ContentType.Application.Json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 15000
        }
    }

    override suspend fun getDeployment(deploymentId: String): VercelDeployment = withRetry {
        try {
            client.get("${config.apiUrl}/v13/deployments/$deploymentId") {
                config.teamId?.let { parameter("teamId", it) }
            }.body()
        } catch (e: ClientRequestException) {
            when (e.response.status) {
                HttpStatusCode.Unauthorized -> throw VercelAuthenticationException(cause = e)
                HttpStatusCode.NotFound -> throw VercelApiException("Deployment not found: $deploymentId", e)
                else -> throw VercelApiException("Failed to get deployment: ${e.message}", e)
            }
        }
    }

    override suspend fun downloadSourceFiles(deployment: VercelDeployment, targetDir: File) {
        withContext(Dispatchers.IO) {
            try {
                // Create target directory if it doesn't exist
                targetDir.mkdirs()

                // Get deployment files list
                val files = withRetry {
                    client.get("${config.apiUrl}/v13/deployments/${deployment.id}/files") {
                        config.teamId?.let { parameter("teamId", it) }
                    }.body<List<VercelFile>>()
                }

                // Download each file with parallel processing
                coroutineScope {
                    files.map { file ->
                        async {
                            val targetFile = File(targetDir, file.name)
                            targetFile.parentFile.mkdirs()
                            downloadFile(deployment.id, file.name, targetFile)
                        }
                    }.awaitAll()
                }
            } catch (e: Exception) {
                throw VercelFileDownloadException("Failed to download deployment files", e)
            }
        }
    }

    override suspend fun listDeployments(limit: Int): List<VercelDeployment> = withRetry {
        try {
            client.get("${config.apiUrl}/v13/deployments") {
                config.teamId?.let { parameter("teamId", it) }
                parameter("limit", limit)
            }.body<DeploymentResponse>().deployments
        } catch (e: ClientRequestException) {
            when (e.response.status) {
                HttpStatusCode.Unauthorized -> throw VercelAuthenticationException(cause = e)
                else -> throw VercelApiException("Failed to list deployments: ${e.message}", e)
            }
        }
    }

    private suspend fun downloadFile(deploymentId: String, fileName: String, targetFile: File) = withRetry {
        try {
            client.get("${config.apiUrl}/v13/deployments/$deploymentId/files/$fileName") {
                config.teamId?.let { parameter("teamId", it) }
            }.body<ByteArray>().let {
                withContext(Dispatchers.IO) {
                    Files.write(targetFile.toPath(), it)
                }
            }
        } catch (e: Exception) {
            throw VercelFileDownloadException("Failed to download file: $fileName", e)
        }
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastException: Exception? = null
        repeat(retryAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < retryAttempts - 1) {
                    delay((retryDelay * (attempt + 1)).milliseconds)
                }
            }
        }
        throw lastException ?: VercelApiException("Operation failed after $retryAttempts attempts")
    }
}

@kotlinx.serialization.Serializable
private data class DeploymentResponse(
    val deployments: List<VercelDeployment>
)

@kotlinx.serialization.Serializable
private data class VercelFile(
    val name: String,
    val size: Long,
    @kotlinx.serialization.SerialName("type")
    val fileType: String
) 