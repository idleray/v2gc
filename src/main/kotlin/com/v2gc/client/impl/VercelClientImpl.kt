package com.v2gc.client.impl

import com.v2gc.client.VercelClient
import com.v2gc.client.exception.VercelApiException
import com.v2gc.client.exception.VercelAuthenticationException
import com.v2gc.client.exception.VercelFileDownloadException
import com.v2gc.model.VercelConfig
import com.v2gc.model.VercelDeployment
import com.v2gc.model.VercelFile
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
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.Base64

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

// 添加数据类来解析响应
@Serializable
private data class FileResponse(
    val data: String  // base64 encoded string
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
                isLenient = true
                prettyPrint = true
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
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 30000
        }
    }

    private val downloadSemaphore = Semaphore(2).also {
        println("Created download semaphore with 2 permits")
    }

    private var semaphoreCounter = AtomicInteger(0)

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
        val response = client.get("${config.apiUrl}/v6/deployments/${deployment.id}/files") {
            config.teamId?.let { parameter("teamId", it) }
        }

        when (response.status) {
            HttpStatusCode.OK -> {
                val files = response.body<List<VercelFile>>()
                if (files.isEmpty()) {
                    throw VercelFileDownloadException("No files found for deployment ${deployment.id}")
                }
                
                // Filter for src directory
                val srcDir = files.find { it.name == "src" && it.type == "directory" }
                    ?: throw VercelFileDownloadException("No src directory found in deployment ${deployment.id}")
                
                println("Found src directory, starting download...")
                targetDir.mkdirs()
                coroutineScope {
                    launch {
                        downloadFileRecursively(deployment.id, srcDir, targetDir)
                    }
                }
            }
            else -> {
                throw VercelFileDownloadException("Failed to get deployment files: ${response.status}")
            }
        }
    }
    
    private suspend fun Semaphore.withPermit(block: suspend () -> Unit) {
        try {
            acquire()
            block()
        } finally {
            release()
        }
    }

    private suspend fun downloadFileRecursively(deploymentId: String, file: VercelFile, directory: File, parentPath: String = "") {
        val currentPath = File(directory, file.name)
        val relativePath = if (parentPath.isEmpty()) file.name else "$parentPath/${file.name}"
        
        when (file.type) {
            "directory" -> {
                println("Processing directory: $relativePath")
                currentPath.mkdirs()
                val filesToDownload = mutableListOf<Triple<VercelFile, File, String>>()
                
                fun collectFiles(vFile: VercelFile, dir: File, path: String) {
                    when (vFile.type) {
                        "directory" -> {
                            val newDir = File(dir, vFile.name)
                            newDir.mkdirs()
                            // 递归处理子文件和子目录
                            vFile.children?.forEach { child ->
                                val newPath = if (path.isEmpty()) vFile.name else "$path/${vFile.name}"
                                collectFiles(child, newDir, newPath)
                            }
                        }
                        "file" -> {
                            filesToDownload.add(Triple(vFile, dir, path))
                        }
                    }
                }
                
                // 收集当前目录下的文件
                file.children?.forEach { child ->
                    // 直接对当前目录的子项进行收集
                    when (child.type) {
                        "file" -> filesToDownload.add(Triple(child, currentPath, relativePath))
                        "directory" -> collectFiles(child, currentPath, relativePath)
                    }
                }
                
                println("Total files to download in $relativePath: ${filesToDownload.size}")
                
                val batchSize = 2 // 减小批次大小，与信号量数量匹配
                filesToDownload.chunked(batchSize).forEachIndexed { index, batch ->
                    println("Processing batch ${index + 1}/${(filesToDownload.size + batchSize - 1) / batchSize} for $relativePath")
                    
                    coroutineScope {
                        val results = batch.map { (vFile, dir, path) ->
                            async {
                                try {
                                    downloadSingleFile(deploymentId, vFile, dir, path)
                                    true
                                } catch (e: Exception) {
                                    println("Failed to download file: ${vFile.name}, error: ${e.message}")
                                    false
                                }
                            }
                        }
                        // 等待当前批次的所有下载完成
                        results.awaitAll()
                    }
                    
                    println("Completed batch ${index + 1} for $relativePath")
                }
            }
            "file" -> {
                val filesToDownload = listOf(Triple(file, directory, parentPath))
                // 单个文件直接下载
                try {
                    downloadSingleFile(deploymentId, file, directory, parentPath)
                } catch (e: Exception) {
                    println("Failed to download file: ${file.name}, error: ${e.message}")
                }
            }
            "lambda" -> {
                println("Skipping lambda file: $relativePath")
            }
        }
    }

    private suspend fun downloadSingleFile(deploymentId: String, file: VercelFile, directory: File, parentPath: String) {
        val currentPath = File(directory, file.name)
        val relativePath = if (parentPath.isEmpty()) file.name else "$parentPath/${file.name}"
        
        var attempt = 1
        val maxAttempts = 5
        var delay = 1000L
        
        try {
            println("Waiting for semaphore permit: $relativePath (Current permits in use: ${semaphoreCounter.get()})")
            downloadSemaphore.withPermit {
                val currentCount = semaphoreCounter.incrementAndGet()
                println("Acquired semaphore permit: $relativePath (Current permits in use: $currentCount)")
                
                try {
                    while (attempt <= maxAttempts) {
                        try {
                            println("Downloading file (attempt $attempt/$maxAttempts): $relativePath")
                            val response = withContext(Dispatchers.IO) {
                                client.get("${config.apiUrl}/v7/deployments/$deploymentId/files/${file.uid}") {
                                    config.teamId?.let { parameter("teamId", it) }
                                    timeout {
                                        requestTimeoutMillis = 30000
                                        connectTimeoutMillis = 15000
                                    }
                                }
                            }
                            
                            when (response.status) {
                                HttpStatusCode.OK -> {
                                    // 解析响应为 FileResponse
                                    val fileResponse = response.body<FileResponse>()
                                    // 解码 base64 数据
                                    val bytes = Base64.getDecoder().decode(fileResponse.data)
                                    withContext(Dispatchers.IO) {
                                        currentPath.writeBytes(bytes)
                                    }
                                    println("Successfully downloaded: $relativePath (Size: ${bytes.size} bytes)")
                                    return@withPermit
                                }
                                HttpStatusCode.TooManyRequests -> {
                                    if (attempt == maxAttempts) {
                                        throw VercelFileDownloadException("Rate limit exceeded for $relativePath after $maxAttempts attempts")
                                    }
                                    println("Rate limit hit for $relativePath, retrying in ${delay/1000} seconds...")
                                    delay(delay)
                                    delay *= 2
                                    attempt++
                                }
                                else -> {
                                    val errorBody = runCatching { response.body<String>() }.getOrNull()
                                    println("Error response for $relativePath: $errorBody")
                                    throw VercelFileDownloadException("Failed to download file $relativePath: ${response.status}")
                                }
                            }
                        } catch (e: Exception) {
                            if (attempt == maxAttempts) {
                                throw e
                            }
                            println("Error downloading $relativePath: ${e.message}, retrying in ${delay/1000} seconds...")
                            delay(delay)
                            delay *= 2
                            attempt++
                        }
                    }
                } finally {
                    val currentCount = semaphoreCounter.decrementAndGet()
                    println("Released semaphore permit: $relativePath (Current permits in use: $currentCount)")
                }
            }
        } catch (e: Exception) {
            println("Failed to download $relativePath after $maxAttempts attempts: ${e.message}")
            throw e
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
            targetFile.parentFile.mkdirs()
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

    suspend fun testDownloadFile(deploymentId: String, fileId: String) {
        println("Starting test download...")
        println("DeploymentId: $deploymentId")
        println("FileId: $fileId")

        withContext(Dispatchers.IO) {
            try {
                val response = withContext(Dispatchers.IO) {
                    client.get("${config.apiUrl}/v7/deployments/$deploymentId/files/$fileId") {
                        config.teamId?.let { parameter("teamId", it) }
                        timeout {
                            requestTimeoutMillis = 180000 // 3 minutes
                            connectTimeoutMillis = 60000 // 1 minute
                        }
                    }
                }

                println("Response received with status: ${response.status}")

                when (response.status) {
                    HttpStatusCode.OK -> {
                        val bytes = response.body<ByteArray>()
                        println("Successfully downloaded file. Size: ${bytes.size} bytes")
                        // Save to a test file
                        val testFile = File("test_download.txt")
                        testFile.writeBytes(bytes)
                        println("File saved to: ${testFile.absolutePath}")
                    }

                    else -> {
                        val errorBody = runCatching { response.body<String>() }.getOrNull()
                        println("Error response: $errorBody")
                        println("Failed with status: ${response.status}")
                    }
                }
            } catch (e: Exception) {
                println("Exception occurred: ${e.javaClass.simpleName}")
                println("Error message: ${e.message}")
                e.printStackTrace()
            }
        }
    }
} 