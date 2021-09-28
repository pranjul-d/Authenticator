package com.softradix.authenticatordemo.utils

 fun <E> MutableList<E>.replaceAll(replacement: E, predicate: (E) -> Boolean) {
    val iterate = listIterator()
    while (iterate.hasNext()) {
        val value = iterate.next()
        if (predicate(value)) iterate.set(replacement)
    }
}