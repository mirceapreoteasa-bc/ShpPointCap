package org.gcap.core

object SampleNaming {
    data class Plan(val name: String, val error: String?, val unusual: Boolean) {
        val canSave: Boolean get() = error == null
    }

    fun plan(gc: GroundCall, existing: (String) -> Boolean, raw: String): Plan {
        var n = raw.trim()
        if (n.endsWith(".dat", ignoreCase = true)) n = n.dropLast(4).trim()
        if (n.isEmpty()) return Plan(n, "Name cannot be empty", false)
        if (n == gc.sampleId) return Plan(n, "Already saved as ${gc.sampleId}.dat", false)
        if (existing(n)) return Plan(n, "Sample $n already exists", false)
        val unusual = FieldChecks.sampleName(n).level != FieldChecks.Level.OK
        return Plan(n, null, unusual)
    }
}
