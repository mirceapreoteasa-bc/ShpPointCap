package org.gcap.core

/**
 * Common storage interface for .dat files and sidecar/spreadsheet exports.
 */
interface GCapStorage {
    fun listDatNames(): List<String>
    fun readBytes(name: String): ByteArray?
    fun writeBytes(name: String, bytes: ByteArray): Boolean
    fun writeSubFolderBytes(subFolder: String, name: String, bytes: ByteArray): Boolean
    fun delete(name: String)
    fun describe(): String
}
