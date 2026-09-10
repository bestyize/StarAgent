package com.starringbox.staragent

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform