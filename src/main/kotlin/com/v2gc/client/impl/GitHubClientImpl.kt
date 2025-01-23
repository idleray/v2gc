package com.v2gc.client.impl

import com.v2gc.client.GitHubClient
import com.v2gc.model.GitHubConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class GitHubClientImpl(
    private val config: GitHubConfig,
    private val httpClient: HttpClient? = null
) : GitHubClient {

    private val client = (httpClient ?: HttpClient(CIO)).config {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                isLenient = true
            })
        }
        install(Auth) {
            bearer {
                loadTokens {
                    BearerTokens(config.token, "")
                }
                sendWithoutRequest { request ->
                    request.url.host == "api.github.com"
                }
            }
        }
        defaultRequest {
            url("https://api.github.com")
            header("Accept", "application/vnd.github.v3+json")
        }
    }

    override suspend fun repositoryExists(name: String): Boolean {
        return try {
            val response = client.get("repos/${config.owner}/$name")
            response.status == HttpStatusCode.OK
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) {
                false
            } else {
                throw e
            }
        }
    }

    override suspend fun createRepository(name: String, description: String?, isPrivate: Boolean): String {
        @Serializable
        data class CreateRepoRequest(
            val name: String,
            val description: String? = null,
            val private: Boolean = true
        )

        @Serializable
        data class CreateRepoResponse(
            val html_url: String
        )

        val response = client.post("user/repos") {
            contentType(ContentType.Application.Json)
            setBody(CreateRepoRequest(name, description, isPrivate))
        }

        return response.body<CreateRepoResponse>().html_url
    }
} 