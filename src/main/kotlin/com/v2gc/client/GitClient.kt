package com.v2gc.client

import java.io.File

interface GitClient {
    fun initRepository(directory: File)
    fun addFiles(files: List<File>)
    fun commit(message: String)
    fun push(branch: String)
    fun setCredentials(username: String, token: String)
    fun setAuthor(name: String, email: String)
} 