package com.v2gc

import com.typesafe.config.ConfigFactory
import io.github.config4k.extract
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    val config = ConfigFactory.load()
    
    // TODO: Initialize services and start the application
    println("V2GC - Vercel to Git Commit")
    println("Starting application...")
} 