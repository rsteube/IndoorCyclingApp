/*
 * Copyright (C) 2026 Snapp Technology Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.indoorbike.shared.fit

/**
 * Minimal hand-rolled writer for the Garmin FIT (Flexible & Interoperable
 * Data Transfer) binary format. Implements just what's needed to encode a
 * power-zone indoor cycling activity that platforms like Intervals.icu,
 * Strava, Garmin Connect, and Training Peaks accept.
 *
 * No third-party dependency, and no Garmin SDK JAR. The format is
 * publicly documented at https://developer.garmin.com/fit/protocol/ — we
 * implement a tiny subset, all bytes accounted for, all CRCs computed
 * locally.
 *
 * Layout we produce:
 *
 *   ┌────────────────────────┐
 *   │ 14-byte header w/ CRC  │
 *   ├────────────────────────┤
 *   │ def file_id            │  <─ each "def" line is a definition message
 *   │ data file_id           │     that establishes a local-message-type
 *   │ def file_creator       │     slot (0..15) and the field layout for
 *   │ data file_creator      │     all subsequent data messages on that slot.
 *   │ def event              │
 *   │ data event (start)     │
 *   │ def record             │
 *   │ data record × N        │
 *   │ def lap                │
 *   │ data lap × M           │
 *   │ data event (stop)      │
 *   │ def session            │
 *   │ data session           │
 *   │ def activity           │
 *   │ data activity          │
 *   ├────────────────────────┤
 *   │ 2-byte file CRC        │
 *   └────────────────────────┘
 *
 * Endianness: little-endian throughout. We pick LE for everything because it
 * matches the host JVM/x86/ARM and avoids per-message endian flipping.
 */
class FitWriter {

    private val out = ArrayList<Byte>(8192)

    val sizeBytes: Int get() = out.size

    fun bytes(): ByteArray = out.toByteArray()

    // ── Low-level byte ops ───────────────────────────────────────────────
    fun u8(v: Int) { out.add((v and 0xFF).toByte()) }
    fun u16(v: Int) { u8(v and 0xFF); u8((v ushr 8) and 0xFF) }
    fun u32(v: Long) {
        u8((v and 0xFFL).toInt())
        u8(((v ushr 8) and 0xFFL).toInt())
        u8(((v ushr 16) and 0xFFL).toInt())
        u8(((v ushr 24) and 0xFFL).toInt())
    }
    fun s8(v: Int) { u8(v) }
    fun s16(v: Int) { u16(v) }
    fun s32(v: Int) { u32(v.toLong() and 0xFFFFFFFFL) }
    fun ascii(s: String, len: Int) {
        // Fixed-length string field, null-padded if shorter.
        val bytes = s.encodeToByteArray()
        for (i in 0 until len) {
            out.add(if (i < bytes.size) bytes[i] else 0)
        }
    }

    // ── Definition message ───────────────────────────────────────────────
    /**
     * Writes a definition message for a global FIT message. `localType` is the
     * 0..15 slot future data messages will reference. `fields` is the ordered
     * list of (fieldDefNum, sizeBytes, baseTypeCode) tuples — must match the
     * fields you'll write in subsequent data messages on this slot.
     *
     * `developerFields` declares custom fields appended after the standard ones,
     * as (fieldDefNum, sizeBytes, developerDataIndex) tuples. Each must have been
     * described by a `field_description` message earlier in the file, and data
     * messages on this slot must write their values after the standard fields, in
     * this same order. Empty (the default) produces a plain definition message
     * with no developer-data flag, byte-for-byte as before.
     */
    fun writeDefinition(
        localType: Int,
        globalMessageNum: Int,
        fields: List<Triple<Int, Int, Int>>,
        developerFields: List<Triple<Int, Int, Int>> = emptyList(),
    ) {
        require(localType in 0..15) { "localType must be 0..15" }
        // Header byte: bit6 set = definition; bit5 set = carries developer
        // fields; low 4 bits = local type.
        val developerBit = if (developerFields.isEmpty()) 0x00 else 0x20
        u8(0x40 or developerBit or localType)
        u8(0)                       // reserved
        u8(0)                       // architecture: 0 = little endian
        u16(globalMessageNum)       // global msg num (LE)
        u8(fields.size)
        for ((fieldNum, sizeBytes, baseType) in fields) {
            u8(fieldNum)
            u8(sizeBytes)
            u8(baseType)
        }
        if (developerFields.isEmpty()) return
        u8(developerFields.size)
        for ((fieldNum, sizeBytes, developerDataIndex) in developerFields) {
            u8(fieldNum)
            u8(sizeBytes)
            u8(developerDataIndex)
        }
    }

    /** Data-message header byte: bits 6-4 zero, low 4 bits = local type. */
    fun writeDataHeader(localType: Int) {
        require(localType in 0..15) { "localType must be 0..15" }
        u8(localType and 0x0F)
    }

    // ── File header (14-byte form, with header CRC) ──────────────────────
    /**
     * Writes the FIT 14-byte header. The data-size field is patched in by
     * [finalizeAndCrc] once the body is fully written.
     */
    fun writePlaceholderHeader() {
        u8(14)              // header size
        u8(0x20)            // protocol version: 2.0
        u16(2140)           // profile version: 21.40
        u32(0)              // data size (patched later)
        u8('.'.code); u8('F'.code); u8('I'.code); u8('T'.code)
        u16(0)              // header CRC (patched later)
    }

    /**
     * Patch in the real data-size, compute + patch the header CRC, then append
     * the file-CRC. Call once, at the very end.
     */
    fun finalizeAndCrc() {
        val total = out.size           // includes header, excludes future CRC
        val dataSize = total - 14L
        // Patch data size at offset 4..7
        out[4] = (dataSize and 0xFFL).toByte()
        out[5] = ((dataSize ushr 8) and 0xFFL).toByte()
        out[6] = ((dataSize ushr 16) and 0xFFL).toByte()
        out[7] = ((dataSize ushr 24) and 0xFFL).toByte()
        // Header CRC: CRC of header bytes 0..11 (the 12 byte fields except the
        // CRC slot itself), patched at offsets 12..13.
        val headerCrc = fitCrc(out, 0, 12)
        out[12] = (headerCrc and 0xFFFF and 0xFF).toByte()
        out[13] = ((headerCrc ushr 8) and 0xFF).toByte()
        // File CRC: CRC of everything written so far, appended.
        val fileCrc = fitCrc(out, 0, out.size)
        u16(fileCrc)
    }

    companion object {
        /**
         * 16-bit CRC over a byte range — the FIT spec's nibble-table algorithm.
         * Same one used by Garmin's SDK and by every off-the-shelf parser.
         */
        private val CRC_TABLE = intArrayOf(
            0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
        )
        fun fitCrc(buf: List<Byte>, from: Int, untilExclusive: Int): Int {
            var crc = 0
            for (i in from until untilExclusive) {
                val b = buf[i].toInt() and 0xFF
                var tmp = CRC_TABLE[crc and 0xF]
                crc = (crc ushr 4) and 0x0FFF
                crc = crc xor tmp xor CRC_TABLE[b and 0xF]

                tmp = CRC_TABLE[crc and 0xF]
                crc = (crc ushr 4) and 0x0FFF
                crc = crc xor tmp xor CRC_TABLE[(b ushr 4) and 0xF]
            }
            return crc and 0xFFFF
        }
    }
}

/**
 * FIT base-type codes — the byte that goes in a definition's per-field
 * descriptor. High bit is set for multi-byte types (endian-relevant).
 */
object FitBaseType {
    const val ENUM    = 0x00
    const val SINT8   = 0x01
    const val UINT8   = 0x02
    const val SINT16  = 0x83
    const val UINT16  = 0x84
    const val SINT32  = 0x85
    const val UINT32  = 0x86
    const val STRING  = 0x07
    const val UINT8Z  = 0x0A
    const val UINT16Z = 0x8B
    const val UINT32Z = 0x8C
    const val BYTE    = 0x0D
}

/**
 * Global message numbers we use — the wire identity of each FIT message type.
 * The full list is in the FIT profile (~80+ types); we only emit a handful.
 */
object FitMessage {
    const val FILE_ID      = 0
    const val FILE_CREATOR = 49
    const val EVENT        = 21
    const val RECORD       = 20
    const val LAP          = 19
    const val SESSION      = 18
    const val ACTIVITY     = 34
    /** Declares a developer-data namespace that custom fields belong to. */
    const val DEVELOPER_DATA_ID = 207
    /** Names and types one custom field within a developer-data namespace. */
    const val FIELD_DESCRIPTION = 206
}

/**
 * The "no value here" sentinels the FIT spec reserves per base type. A reader
 * that sees one treats the field as absent for that message, which is how an
 * interval with no cadence ask is distinguished from one asking for 0 rpm.
 */
object FitInvalid {
    const val UINT8  = 0xFF
    const val UINT16 = 0xFFFF
    const val UINT32 = 0xFFFF_FFFFL
}

/**
 * FIT timestamps are seconds since this epoch, NOT since Unix epoch.
 * UTC 1989-12-31T00:00:00 = Unix 631065600.
 */
const val FIT_EPOCH_OFFSET_SECONDS: Long = 631_065_600L

/** Convert Unix milliseconds to FIT seconds. */
fun unixMillisToFit(millis: Long): Long = (millis / 1000L) - FIT_EPOCH_OFFSET_SECONDS
