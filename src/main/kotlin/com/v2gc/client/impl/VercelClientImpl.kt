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
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class ApiResponse<T>(
    val deployment: T? = null,
    val deployments: List<T>? = null,
    val files: List<T>? = null,
    val error: ErrorResponse? = null
)

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
    val invalidToken: Boolean = false
)

@Serializable
data class VercelFile(
    val name: String,
    val size: Long,
    @SerialName("type")
    val fileType: String,
    val children: List<VercelFile>? = null
)

class VercelClientImpl(
    private val config: VercelConfig,
    private val httpClient: HttpClient? = null,
    private val retryAttempts: Int = 3,
    private val retryDelay: Long = 1000
) : VercelClient {
    private val client = (httpClient ?: HttpClient(CIO)).config {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
        install(Logging) {
            level = LogLevel.INFO
        }
        install(Auth) {
            bearer {
                loadTokens {
                    BearerTokens(config.token, "")
                }
            }
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 15000
        }
    }

    override suspend fun getDeployment(deploymentId: String): VercelDeployment = withRetry {
        try {
            val response = client.get("${config.apiUrl}/v6/deployments/$deploymentId") {
                config.teamId?.let { parameter("teamId", it) }
            }

            when (response.status) {
                HttpStatusCode.OK -> {
                    response.body<ApiResponse<VercelDeployment>>().deployment
                        ?: throw VercelApiException("No deployment data returned")
                }
                HttpStatusCode.Unauthorized -> {
                    val apiResponse = response.body<ApiResponse<VercelDeployment>>()
                    if (apiResponse.error?.invalidToken == true) {
                        throw VercelAuthenticationException(apiResponse.error.message)
                    }
                    throw VercelAuthenticationException()
                }
                HttpStatusCode.NotFound -> throw VercelApiException("Deployment not found: $deploymentId")
                else -> {
                    val apiResponse = runCatching { response.body<ApiResponse<VercelDeployment>>() }.getOrNull()
                    throw VercelApiException(
                        apiResponse?.error?.message ?: "Failed to get deployment: ${response.status}"
                    )
                }
            }
        } catch (e: Exception) {
            when (e) {
                is VercelAuthenticationException, is VercelApiException -> throw e
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
                val response = withRetry {
                    client.get("${config.apiUrl}/v6/deployments/${deployment.id}/files") {
                        config.teamId?.let { parameter("teamId", it) }
                    }.body<ApiResponse<VercelFile>>()
                }

                val files = response.files ?: throw VercelApiException("No files found in deployment")

                // Download files recursively
                coroutineScope {
                    files.map { file ->
                        async {
                            downloadFileRecursively(deployment.id, file, targetDir)
                        }
                    }.awaitAll()
                }
            } catch (e: Exception) {
                throw VercelFileDownloadException("Failed to download deployment files", e)
            }
        }
    }

    private suspend fun downloadFileRecursively(deploymentId: String, file: VercelFile, targetDir: File) {
        val targetPath = File(targetDir, file.name)
        
        when (file.fileType) {
            "directory" -> {
                targetPath.mkdirs()
                file.children?.forEach { child ->
                    downloadFileRecursively(deploymentId, child, targetPath)
                }
            }
            "file" -> {
                targetPath.parentFile.mkdirs()
                downloadFile(deploymentId, file.name, targetPath)
            }
        }
    }

    override suspend fun listDeployments(limit: Int): List<VercelDeployment> = withRetry {
        try {
            val response = client.get("${config.apiUrl}/v6/deployments") {
                config.teamId?.let { parameter("teamId", it) }
                parameter("limit", limit)
            }.body<ApiResponse<VercelDeployment>>()

            response.deployments ?: throw VercelApiException("No deployments returned")
        } catch (e: ClientRequestException) {
            when (e.response.status) {
                HttpStatusCode.Unauthorized -> {
                    val response = e.response.body<ApiResponse<List<VercelDeployment>>>()
                    if (response.error?.invalidToken == true) {
                        throw VercelAuthenticationException(response.error.message, e)
                    }
                    throw VercelAuthenticationException(cause = e)
                }
                else -> {
                    val response = runCatching { e.response.body<ApiResponse<List<VercelDeployment>>>() }.getOrNull()
                    throw VercelApiException(
                        response?.error?.message ?: "Failed to list deployments: ${e.message}",
                        e
                    )
                }
            }
        }
    }

    private suspend fun downloadFile(deploymentId: String, filePath: String, targetFile: File) = withRetry {
        try {
            client.get("${config.apiUrl}/v7/deployments/$deploymentId/files/$filePath") {
                config.teamId?.let { parameter("teamId", it) }
            }.body<ByteArray>().let {
                withContext(Dispatchers.IO) {
                    Files.write(targetFile.toPath(), it)
                }
            }
        } catch (e: Exception) {
            throw VercelFileDownloadException("Failed to download file: $filePath", e)
        }
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastException: Exception? = null
        repeat(retryAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (e is VercelAuthenticationException) {
                    throw e
                }
                if (attempt < retryAttempts - 1) {
                    delay((retryDelay * (attempt + 1)).milliseconds)
                }
            }
        }
        throw lastException ?: VercelApiException("Operation failed after $retryAttempts attempts")
    }
} 