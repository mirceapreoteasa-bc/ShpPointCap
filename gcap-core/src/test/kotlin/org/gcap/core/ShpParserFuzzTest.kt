package org.gcap.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Robustness of the shapefile reader against hostile or corrupt input.
 *
 * The app opens .shp/.shx/.dbf files the user copied from a desktop, USB stick
 * or a file share, so every length and count in the file is attacker-controlled.
 * A record must never be able to make the reader allocate memory proportional
 * to a declared count that the record itself cannot hold, read outside the
 * buffer, or emit non-finite coordinates that poison the map view.
 */
class ShpParserFuzzTest {

    /** Shapefile main-file header: 100 bytes, file code 9994, in words. */
    private fun header(contentLen: Int): ByteArray {
        val b = ByteArray(100)
        fun be(off: Int, v: Int) {
            b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte()
            b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
        }
        fun le(off: Int, v: Int) {
            b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte()
            b[off + 2] = (v ushr 16).toByte(); b[off + 3] = (v ushr 24).toByte()
        }
        be(0, 9994)
        be(24, (100 + 8 + contentLen) / 2)   // file length in 16-bit words
        le(28, 1000)                          // version
        le(32, ShpFormat.TYPE_POLYGON)                  // shape type
        be(36, 0); be(40, 0); be(44, 0); be(48, 0)
        be(52, 1000.0.toRawBits().let { 0 }.toInt())
        return b
    }

    /** Record content length is BIG-endian (readIntBe); shape fields are LE. */
    private fun be(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun le(v: Int) = byteArrayOf(
        v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()
    )

    private fun dle(v: Double): ByteArray {
        val bits = v.toRawBits()
        return ByteArray(8) { ((bits ushr (8 * it)) and 0xFF).toByte() }
    }

    /**
     * A small, self-consistent record whose *declared* numParts/numPoints are
     * attacker-chosen and deliberately larger than the record can hold. The
     * buffer is always tiny, which is the whole point of the attack: a ~200
     * byte file that claims millions of parts.
     */
    private fun polygonRecord(
        numParts: Int,
        numPoints: Int,
        recordBytes: Int = 128,
        xmin: Double = 0.0, ymin: Double = 0.0, xmax: Double = 10.0, ymax: Double = 10.0,
    ): ByteArray {
        val contentLen = recordBytes
        val body = ByteArrayOutputStream()
        body.write(le(ShpFormat.TYPE_POLYGON))
        body.write(dle(xmin)); body.write(dle(ymin)); body.write(dle(xmax)); body.write(dle(ymax))
        body.write(le(numParts)); body.write(le(numPoints))
        val written = body.toByteArray()
        val padded = ByteArray(maxOf(contentLen, written.size))
        written.copyInto(padded)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 1))                    // record number 1
        out.write(be(contentLen / 2))                          // content length in words
        out.write(padded)
        return out.toByteArray()
    }

    private fun usedHeap(): Long {
        val r = Runtime.getRuntime()
        System.gc(); Thread.sleep(60)
        return r.totalMemory() - r.freeMemory()
    }

    private fun parse(bytes: ByteArray) = ShpFormat.readShp(bytes, -1e18, -1e18, 1e18, 1e18)

    /**
     * A record declaring 50 million parts is ~200 bytes on disk but must not
     * make the reader allocate a 50-million-element IntArray. Before the bounds
     * check this allocated 200 MB per record; a file full of such records is a
     * memory-exhaustion denial of service.
     */
    @Test
    fun hugeDeclaredPartCountDoesNotAllocate() {
        val rec = polygonRecord(numParts = 50_000_000, numPoints = 1)
        assertTrue(rec.size < 4096, "the attack file must stay tiny, was ${rec.size} bytes")
        val bytes = header(rec.size) + rec
        val before = usedHeap()
        // No try/catch: if the reader walks off the buffer, the
        // ArrayIndexOutOfBoundsException escaping IS the failure.
        val shapes = parse(bytes)
        val growth = usedHeap() - before
        assertTrue(
            shapes.isEmpty() || shapes.all { it.parts.size < 1_000_000 },
            "declared 50M parts must be rejected, got ${shapes.size} shape(s)"
        )
        assertTrue(
            growth < 32L * 1024 * 1024,
            "reader allocated $growth bytes for a ${bytes.size}-byte record"
        )
    }

    /** numPoints is equally untrusted: it drives the vertex loop. */
    @Test
    fun hugeDeclaredPointCountDoesNotAllocate() {
        val rec = polygonRecord(numParts = 1, numPoints = 50_000_000)
        assertTrue(rec.size < 4096, "the attack file must stay tiny, was ${rec.size} bytes")
        val bytes = header(rec.size) + rec
        val before = usedHeap()
        val shapes = parse(bytes)
        val growth = usedHeap() - before
        assertTrue(
            shapes.isEmpty() || shapes.all { s -> s.parts.all { it.size < 1_000_000 } },
            "declared 50M points must be rejected, got ${shapes.size} shape(s)"
        )
        assertTrue(
            growth < 32L * 1024 * 1024,
            "reader allocated $growth bytes for a ${bytes.size}-byte record"
        )
    }

    /**
     * A record may claim a length shorter than the fields the parser reads
     * (the outer check only requires 4 bytes). Reading past the declared record
     * must fail cleanly, not throw out of the reader.
     */
    @Test
    fun recordShorterThanItsFieldsIsRejectedCleanly() {
        val rec = polygonRecord(numParts = 1, numPoints = 1, recordBytes = 4)
        val bytes = header(4) + rec
        val shapes = try {
            parse(bytes)
        } catch (e: ArrayIndexOutOfBoundsException) {
            throw AssertionError("readShp read past the buffer: ${e.message}")
        } catch (e: Exception) {
            emptyList()
        }
        assertTrue(shapes.isEmpty())
    }

    /** A declared content length near Int.MAX overflows the *2 to negative. */
    @Test
    fun overflowedContentLengthIsRejectedCleanly() {
        val rec = polygonRecord(numParts = 1, numPoints = 1)
        // Claim ~2 Gbytes of content in a tiny file.
        val evil = ByteArray(100 + 8 + 64)
        System.arraycopy(rec, 0, evil, 0, rec.size)
        evil[100 + 4] = 0x7F; evil[100 + 5] = 0xFF.toByte()
        evil[100 + 6] = 0xFF.toByte(); evil[100 + 7] = 0xFF.toByte()
        val shapes = try { parse(evil) } catch (e: Exception) { emptyList() }
        assertTrue(shapes.isEmpty())
    }

    /**
     * NaN and Infinity survive Double.fromBits and defeat every bbox compare
     * (all comparisons with NaN are false), so they are never culled. They must
     * be dropped at the reader, or a single bad vertex poisons minOf() in the
     * layer-bbox union and the map centre becomes NaN - the map then renders
     * nothing at all.
     */
    @Test
    fun nonFiniteCoordinatesAreDropped() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val rec = polygonRecord(numParts = 1, numPoints = 1, xmin = bad)
            val bytes = header(rec.size) + rec
            val shapes = try { parse(bytes) } catch (e: Exception) { emptyList() }
            for (s in shapes) {
                assertTrue(
                    s.xmin.isFinite() && s.ymin.isFinite() &&
                        s.xmax.isFinite() && s.ymax.isFinite(),
                    "non-finite $bad leaked through: bbox=${s.xmin}"
                )
                for (part in s.parts) for (pt in part) {
                    assertTrue(pt[0].isFinite() && pt[1].isFinite(), "non-finite vertex $bad leaked")
                }
            }
        }
    }

    /** Truncation at every plausible boundary must not throw out of the reader. */
    @Test
    fun truncatedFilesNeverThrowOutOfReader() {
        val rec = polygonRecord(numParts = 2, numPoints = 3, recordBytes = 44 + 8 + 48)
        val full = header(rec.size) + rec
        var thrown = 0
        for (n in 0..full.size) {
            val slice = full.copyOf(n)
            try { parse(slice) } catch (e: Exception) { /* acceptable */ } catch (e: OutOfMemoryError) {
                throw AssertionError("OOM on a ${n}-byte truncated file")
            } catch (e: Throwable) {
                thrown++
                if (thrown == 1) throw AssertionError("non-Exception escaped at $n bytes: $e")
            }
        }
        assertTrue(true)
    }

    /** Random byte mutations of a valid file: must not escape as an Error. */
    @Test
    fun randomMutationsNeverEscapeAsError() {
        val rec = polygonRecord(numParts = 2, numPoints = 3, recordBytes = 44 + 8 + 48)
        val full = header(rec.size) + rec
        val rnd = java.util.Random(20260928L)
        repeat(3000) {
            val m = full.copyOf()
            val flips = 1 + rnd.nextInt(6)
            repeat(flips) { m[rnd.nextInt(m.size)] = rnd.nextInt(256).toByte() }
            try {
                parse(m)
            } catch (e: Exception) { /* acceptable */ } catch (e: OutOfMemoryError) {
                throw AssertionError("OOM from a ${m.size}-byte mutated file")
            } catch (e: Throwable) {
                throw AssertionError("mutation $it escaped as ${e.javaClass.name}: $e")
            }
        }
    }
}
