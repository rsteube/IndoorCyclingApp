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
package app.indoorbike.shared.sync

import kotlinx.serialization.json.Json

/**
 * JSON read/write for [BackupDocument]. Lenient on read so a slice from a
 * newer minor revision (with extra fields) parses cleanly; strict-version
 * on the major schemaVersion so an incompatibly-newer slice surfaces a
 * clear error rather than silently dropping fields.
 *
 * Pretty-printed on write for human-debuggability — the file is intended
 * to live in the rider's cloud storage where they might open it directly.
 */
object BackupSerializer {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(document: BackupDocument): String =
        json.encodeToString(BackupDocument.serializer(), document)

    /**
     * @throws BackupParseException if the JSON is malformed or its
     * schemaVersion exceeds [BACKUP_SCHEMA_VERSION_MAX_SUPPORTED]. The
     * exception's message is end-user-presentable.
     */
    fun decode(text: String): BackupDocument {
        val document = try {
            json.decodeFromString(BackupDocument.serializer(), text)
        } catch (t: Throwable) {
            throw BackupParseException("Couldn't read backup file: ${t.message}", t)
        }
        if (document.schemaVersion > BACKUP_SCHEMA_VERSION_MAX_SUPPORTED) {
            throw BackupParseException(
                "Backup file uses format v${document.schemaVersion}, " +
                    "this build understands up to v$BACKUP_SCHEMA_VERSION_MAX_SUPPORTED. " +
                    "Update the app to read it."
            )
        }
        return document
    }
}

class BackupParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
