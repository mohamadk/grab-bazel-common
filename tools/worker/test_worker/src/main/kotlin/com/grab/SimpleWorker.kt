package com.grab

import io.bazel.createWorker

fun main(args: Array<String>) {
    createWorker(args = args) {
        print(it.contentToString())
    }.run(args)
}