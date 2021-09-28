package com.softradix.authenticatordemo.utils

object Utilities {
     fun millisUntilNextUpdate(): Long {
        return (30 * 1000) - System.currentTimeMillis() % (30 * 1000)
    }
}