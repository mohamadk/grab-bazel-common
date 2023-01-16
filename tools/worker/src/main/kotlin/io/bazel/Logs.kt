package io.bazel

import java.io.File
import java.util.*

class Logs private constructor(private val file: File = File("/Users/mohammad.khaleghi/StudioProjects/tmp/logs:${Date().minutes}:${Date().seconds}.txt")) {
    companion object{
        val logs=Logs()

    }
    init {
        file.createNewFile()
    }

    @Synchronized
    fun log(message: String) {
        file.appendText("$message\n")
    }

    @Synchronized
    fun log(exception: java.lang.Exception) {
        file.appendText("${exception.message}\n")
        with(file.printWriter()) {
            exception.printStackTrace(this)
            close()
        }
    }
}