package org.gcap.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShpDbfFieldsTest {

    @Test
    fun twoFieldEmptyTableRoundTrips() {
        val bytes = ShpFormat.buildDbfFields(
            listOf("NAME" to 15, "NOTE" to 15),
            emptyList(),
        )
        val dbf = ShpFormat.dbfHeader(bytes)
        assertEquals(0, dbf.numRecords)
        assertEquals(2, dbf.fields.size)
        assertEquals("NAME", dbf.fields[0].name)
        assertEquals('C', dbf.fields[0].type)
        assertEquals(15, dbf.fields[0].len)
        assertEquals("NOTE", dbf.fields[1].name)
        assertEquals(15, dbf.fields[1].len)
        assertTrue(ShpFormat.readDbfRows(bytes).isEmpty())
    }

    @Test
    fun twoFieldTableWithRowsRoundTrips() {
        val bytes = ShpFormat.buildDbfFields(
            listOf("NAME" to 15, "NOTE" to 15),
            listOf(
                listOf("PT001", "first point"),
                listOf("PT002", "second point"),
            ),
        )
        val dbf = ShpFormat.dbfHeader(bytes)
        assertEquals(2, dbf.numRecords)
        val rows = ShpFormat.readDbfRows(bytes)
        assertEquals(2, rows.size)
        assertEquals(listOf("PT001", "first point"), rows[0].values)
        assertEquals(listOf("PT002", "second point"), rows[1].values)
    }

    @Test
    fun fieldNamesAreUppercasedAndCappedAtTen() {
        val bytes = ShpFormat.buildDbfFields(
            listOf("averylongfieldname" to 10, "ok" to 10),
            emptyList(),
        )
        val dbf = ShpFormat.dbfHeader(bytes)
        assertEquals("AVERYLONGF", dbf.fields[0].name)
        assertEquals("OK", dbf.fields[1].name)
    }
}
