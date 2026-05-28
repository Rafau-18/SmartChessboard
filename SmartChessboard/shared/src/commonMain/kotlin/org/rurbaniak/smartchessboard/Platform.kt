package org.rurbaniak.smartchessboard

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform