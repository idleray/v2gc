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
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig
import org.eclipse.jgit.util.FS
import java.io.File
import com.jcraft.jsch.Session
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.lib.ObjectReader

class GitHubClientImpl(
    private val config: GitHubConfig,
    private val httpClient: HttpClient? = null
) : GitHubClient {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = (httpClient ?: HttpClient(CIO)).config {
        install(ContentNegotiation) {
            json(json)
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

    private val repoUrl = "git@github.com:${config.owner}/${config.repo}.git"

    @Serializable
    private data class CreateRepoRequest(
        val name: String,
        val description: String?,
        val private: Boolean,
        val auto_init: Boolean,
        val visibility: String
    )

    @Serializable
    private data class CreateRepoResponse(
        val html_url: String,
        val clone_url: String,
        val ssh_url: String
    )

    override suspend fun repositoryExists(): Boolean {
        return try {
            val response = client.get("repos/${config.owner}/${config.repo}")
            response.status == HttpStatusCode.OK
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) {
                false
            } else {
                throw e
            }
        }
    }

    override suspend fun createRepository(): String {
        val createRequest = CreateRepoRequest(
            name = config.repo,
            description = "Migrated from Vercel",
            private = true,
            auto_init = false,
            visibility = "private"
        )
        
//        println("Creating repository with request: ${json.encodeToString(CreateRepoRequest.serializer(), createRequest)}")
        
        val response = client.post("user/repos") {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
        }

        when (response.status) {
            HttpStatusCode.Created -> {
                val repoResponse = response.body<CreateRepoResponse>()
                println("Created repository: ${repoResponse.html_url}")
                return repoResponse.html_url
            }
            HttpStatusCode.Unauthorized -> {
                throw IllegalStateException("GitHub authentication failed. Please check your token.")
            }
            HttpStatusCode.UnprocessableEntity -> {
                throw IllegalStateException("Repository already exists or name is invalid.")
            }
            else -> {
                throw IllegalStateException("Failed to create repository: ${response.status}")
            }
        }
    }

    override fun cloneRepository(directory: File) {
        Git.cloneRepository()
            .setURI(repoUrl)
            .setDirectory(directory)
            .setTransportConfigCallback { transport ->
                if (transport is SshTransport) {
                    transport.sshSessionFactory = object : JschConfigSessionFactory() {
                        override fun configure(host: OpenSshConfig.Host, session: Session) {
                            session.setConfig("StrictHostKeyChecking", "no")
                        }
                    }
                }
            }
            .call()
    }

    override fun commitAndPush(directory: File, deploymentId: String) {
        Git.open(directory).use { git ->
            // Add all files to index
            git.add()
                .addFilepattern(".")
                .call()

            // Get repository status
            val status = git.status().call()
            
            // Check if there are any changes
            val hasChanges = status.hasUncommittedChanges() || 
                status.added.isNotEmpty() || 
                status.changed.isNotEmpty() || 
                status.removed.isNotEmpty()

            if (!hasChanges) {
                println("No changes detected, skipping commit and push")
                return
            }

            // Print changes summary
            println("Changes detected:")
            status.added.forEach { println("ADD $it") }
            status.changed.forEach { println("MODIFY $it") }
            status.removed.forEach { println("DELETE $it") }
            status.untracked.forEach { println("UNTRACKED $it") }

            // Commit
            val message = "Vercel to github deployment id: $deploymentId"
            git.commit()
                .setMessage(message)
                .call()

            // Push to main branch
            git.push()
                .setTransportConfigCallback { transport ->
                    if (transport is SshTransport) {
                        transport.sshSessionFactory = object : JschConfigSessionFactory() {
                            override fun configure(host: OpenSshConfig.Host, session: Session) {
                                session.setConfig("StrictHostKeyChecking", "no")
                            }
                        }
                    }
                }
                .call()

            println("Changes committed and pushed to main branch")
        }
    }
} 