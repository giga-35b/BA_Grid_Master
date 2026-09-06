package com.bagridmaster.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CellAddressTest {
    @Test
    fun formatsExcelStyleCoordinates() {
        assertEquals("A1", cellAddress(row = 0, column = 0))
        assertEquals("F3", cellAddress(row = 2, column = 5))
        assertEquals("Z5", cellAddress(row = 4, column = 25))
        assertEquals("AA1", cellAddress(row = 0, column = 26))
    }

    @Test
    fun rejectsNegativeCoordinates() {
        assertThrows(IllegalArgumentException::class.java) { cellAddress(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { cellAddress(0, -1) }
    }
}
