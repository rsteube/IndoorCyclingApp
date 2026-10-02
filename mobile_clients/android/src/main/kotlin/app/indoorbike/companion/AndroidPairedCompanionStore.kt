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
package app.indoorbike.companion

import android.content.Context
import app.indoorbike.shared.companion.JsonPairedCompanionStore
import app.indoorbike.shared.companion.PairedCompanionStore
import app.indoorbike.shared.companion.TextFile
import app.indoorbike.util.AtomicFiles
import java.io.File

/**
 * The rider's trusted companions on Android: [JsonPairedCompanionStore], shared
 * with iOS, over `filesDir/paired_companions.json` through [AtomicFiles].
 */
fun AndroidPairedCompanionStore(appContext: Context): PairedCompanionStore {
    val file = File(appContext.filesDir, "paired_companions.json")
    return JsonPairedCompanionStore(object : TextFile {
        override fun read(): String? = AtomicFiles.readText(file)
        override fun write(text: String) = AtomicFiles.writeText(file, text)
        override fun delete() = AtomicFiles.delete(file)
    })
}
