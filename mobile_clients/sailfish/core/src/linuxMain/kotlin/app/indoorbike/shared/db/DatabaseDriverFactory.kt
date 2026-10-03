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
package app.indoorbike.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.indoorbike.shared.sailfish.SailfishPaths

/**
 * `history.db` in the app's data folder, the one place under Sailjail it
 * survives a restart. The driver's default folder is not that. Foreign keys
 * on, as on Android.
 */
actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver = NativeSqliteDriver(
        schema = HistoryDatabase.Schema,
        name = "history.db",
        onConfiguration = { config ->
            config.copy(
                extendedConfig = config.extendedConfig.copy(
                    basePath = SailfishPaths.dataDir,
                    foreignKeyConstraints = true,
                ),
            )
        },
    )
}
