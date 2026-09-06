package com.bagridmaster.app.analysis

/** Converts zero-based board coordinates to Excel-style addresses such as A1 and F3. */
fun cellAddress(row: Int, column: Int): String {
    require(row >= 0) { "Row must be non-negative" }
    require(column >= 0) { "Column must be non-negative" }

    var remaining = column
    val letters = StringBuilder()
    do {
        letters.append(('A'.code + remaining % 26).toChar())
        remaining = remaining / 26 - 1
    } while (remaining >= 0)

    return letters.reverse().append(row + 1).toString()
}

