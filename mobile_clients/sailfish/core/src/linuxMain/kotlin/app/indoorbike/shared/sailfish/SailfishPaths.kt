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
package app.indoorbike.shared.sailfish

/**
 * The folders the app may keep files in, handed over by the Qt side at start.
 *
 * Sailjail lets an app write in exactly three places, named by the
 * `[X-Sailjail]` section of its desktop file:
 * `~/.local/share/<Org>/<App>`, `~/.config/<Org>/<App>` and
 * `~/.cache/<Org>/<App>`. A write anywhere else succeeds, lands in a scratch
 * layer and is gone at the next launch, with no error. Sailjail sets no
 * environment variable for them, and a pushed debug build runs outside it with
 * folders of its own, so the core never works one out: Qt's `QStandardPaths`
 * does, and passes it in.
 *
 * Until then they point at the working directory, which is what the tests use.
 */
object SailfishPaths {
    var dataDir: String = "."
        private set
    var configDir: String = "."
        private set
    var cacheDir: String = "."
        private set

    fun configure(dataDir: String, configDir: String, cacheDir: String) {
        this.dataDir = dataDir
        this.configDir = configDir
        this.cacheDir = cacheDir
    }

    fun data(name: String) = "$dataDir/$name"
    fun config(name: String) = "$configDir/$name"
    fun cache(name: String) = "$cacheDir/$name"
}
