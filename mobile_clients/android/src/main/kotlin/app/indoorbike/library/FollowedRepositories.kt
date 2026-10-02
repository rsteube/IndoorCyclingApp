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
package app.indoorbike.library

import app.indoorbike.shared.repo.TextStore
import app.indoorbike.shared.util.Log
import java.io.File

/**
 * Where [app.indoorbike.shared.repo.FollowedRepositories] keeps its files on
 * Android: under `filesDir/followed/`, each written to a temporary file and
 * renamed into place so a crash leaves the old text rather than half.
 */
class AndroidTextStore(private val dir: File) : TextStore {
    override fun read(name: String): String? {
        val file = File(dir, name)
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    override fun write(name: String, text: String) {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) file.writeText(text, Charsets.UTF_8)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    override fun delete(name: String) {
        if (!File(dir, name).delete()) Log.d("AndroidTextStore", "Nothing to delete at $name")
    }
}
