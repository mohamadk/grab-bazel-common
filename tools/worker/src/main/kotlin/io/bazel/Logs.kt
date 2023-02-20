package io.bazel

import java.io.File
import java.util.*

class Logs private constructor(private val file: File = File("/Users/svc.tpm.mpcicd.developer-cache/StudioProjects/tmp/logs:${Date().minutes}:${Date().seconds}.txt")) {

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    companion object{
        val logs=Logs()

    }
    init {
        file.createNewFile()
    }

    @Synchronized
    fun log(message: String) {

        file.appendText("${currentDate()}: $message\n")
    }

    @Synchronized
    fun log(exception: java.lang.Exception) {
        file.appendText("${exception.message}\n")
        with(file.printWriter()) {
            exception.printStackTrace(this)
            close()
        }
    }

    fun currentDate():String{
        return LocalDateTime.now().format(formatter)
    }
}