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

import kotlin.test.assertEquals

// A FIT reader for the tests: every data message in file order, with every
// standard and developer field's raw bytes, so a test can read a field back the
// way another service would rather than trusting the writer.

internal class FitMessageRead(val global: Int, val fields: Map<Int, ByteArray>, val developer: Map<Int, ByteArray>) {
    fun number(field: Int): Long {
        val bytes = fields.getValue(field)
        var value = 0L
        for (i in bytes.indices) value = value or ((bytes[i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    fun text(field: Int): String = fields.getValue(field).decodeToString().substringBefore('\u0000')

    fun developerText(field: Int): String = developer.getValue(field).decodeToString().substringBefore('\u0000')
}

internal class FitFileRead(val messages: List<FitMessageRead>) {
    fun all(global: Int) = messages.filter { it.global == global }
    fun single(global: Int) = all(global).single()
}

internal fun readFit(bytes: ByteArray): FitFileRead {
    class Def(val global: Int, val fields: List<Pair<Int, Int>>, val developer: List<Pair<Int, Int>>)
    val definitions = HashMap<Int, Def>()
    val out = mutableListOf<FitMessageRead>()
    fun u8At(i: Int) = bytes[i].toInt() and 0xFF
    var pos = 14
    val end = bytes.size - 2
    while (pos < end) {
        val header = u8At(pos); pos += 1
        val localType = header and 0x0F
        if (header and 0x40 != 0) {
            pos += 2
            val global = u8At(pos) or (u8At(pos + 1) shl 8); pos += 2
            val count = u8At(pos); pos += 1
            val fields = (0 until count).map { i -> u8At(pos + i * 3) to u8At(pos + i * 3 + 1) }
            pos += count * 3
            val developer = if (header and 0x20 == 0) emptyList() else {
                val devCount = u8At(pos); pos += 1
                (0 until devCount).map { i -> u8At(pos + i * 3) to u8At(pos + i * 3 + 1) }
                    .also { pos += devCount * 3 }
            }
            definitions[localType] = Def(global, fields, developer)
        } else {
            val def = definitions[localType] ?: error("data message for undefined local type $localType")
            val fields = def.fields.associate { (num, size) ->
                (num to bytes.copyOfRange(pos, pos + size)).also { pos += size }
            }
            val developer = def.developer.associate { (num, size) ->
                (num to bytes.copyOfRange(pos, pos + size)).also { pos += size }
            }
            out += FitMessageRead(def.global, fields, developer)
        }
    }
    assertEquals(end, pos, "reader must consume the body exactly")
    return FitFileRead(out)
}
