package com.example.app

/** Stands in for app code in initiator tests (outside the sidekick's packages). */
object AppCode {
    fun scheduleFromHere(block: () -> Unit) = block()

    fun <T> callFromHere(block: () -> T): T = block()
}
