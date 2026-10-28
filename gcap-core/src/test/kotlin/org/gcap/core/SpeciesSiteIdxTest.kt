package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Species codes must point at the DLL entry with the same name (sindex33.dll Sindex_SpecName). */
class SpeciesSiteIdxTest {
    @Test
    fun `mapped codes match the DLL species names`() {
        assertEquals(41, SpeciesSiteIdx.indexFor("FDC"))  // Coastal Douglas-fir
        assertEquals(22, SpeciesSiteIdx.indexFor("CW"))   // Western redcedar
        assertEquals(48, SpeciesSiteIdx.indexFor("HW"))   // Western hemlock
        assertEquals(8, SpeciesSiteIdx.indexFor("AT"))    // Trembling aspen
        assertEquals(11, SpeciesSiteIdx.indexFor("BA"))   // Amabilis fir
        assertEquals(47, SpeciesSiteIdx.indexFor("HM"))   // Mountain hemlock
        assertEquals(109, SpeciesSiteIdx.indexFor("SS"))  // Sitka spruce
        assertEquals(90, SpeciesSiteIdx.indexFor("PLI"))  // Lodgepole pine (own curve)
        assertEquals(88, SpeciesSiteIdx.indexFor("PL"))
    }

    @Test
    fun `unmapped and blank codes have no curve`() {
        assertEquals(-1, SpeciesSiteIdx.indexFor("O"))
        assertEquals(-1, SpeciesSiteIdx.indexFor("F"))
        assertEquals(-1, SpeciesSiteIdx.indexFor("C"))
        assertEquals(-1, SpeciesSiteIdx.indexFor("X"))
        assertEquals(-1, SpeciesSiteIdx.indexFor(""))
        assertEquals(false, SpeciesSiteIdx.hasCurve("ZZZ"))
    }
}
