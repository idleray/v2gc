package com.v2gc.client.impl

import com.v2gc.client.GitClient
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

class GitClientImpl : GitClient {
    private lateinit var git: Git
    private lateinit var credentialsProvider: UsernamePasswordCredentialsProvider
    private lateinit var authorName: String
    private lateinit var authorEmail: String

    override fun initRepository(directory: File) {
        try {
            git = if (File(directory, ".git").exists()) {
                Git.open(directory)
            } else {
                Git.init().setDirectory(directory).call()
            }
        } catch (e: GitAPIException) {
            throw RuntimeException("Failed to initialize Git repository", e)
        }
    }

    override fun addFiles(files: List<File>) {
        try {
            val addCommand = git.add()
            files.forEach { file ->
                val relativePath = file.relativeTo(git.repository.workTree).path
                addCommand.addFilepattern(relativePath)
            }
            addCommand.call()
        } catch (e: GitAPIException) {
            throw RuntimeException("Failed to add files to Git", e)
        }
    }

    override fun commit(message: String) {
        try {
            git.commit()
                .setMessage(message)
                .setAuthor(authorName, authorEmail)
                .call()
        } catch (e: GitAPIException) {
            throw RuntimeException("Failed to commit changes", e)
        }
    }

    override fun push(branch: String) {
        try {
            val branchRefSpec = RefSpec("refs/heads/$branch:refs/heads/$branch")
            git.push()
                .setCredentialsProvider(credentialsProvider)
                .setRemote("origin")
                .setRefSpecs(branchRefSpec)
                .call()
        } catch (e: GitAPIException) {
            throw RuntimeException("Failed to push changes", e)
        }
    }

    override fun setCredentials(username: String, token: String) {
        credentialsProvider = UsernamePasswordCredentialsProvider(username, token)
    }

    override fun setAuthor(name: String, email: String) {
        authorName = name
        authorEmail = email
    }
} 