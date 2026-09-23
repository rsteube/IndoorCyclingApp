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
 */package app.indoorbike.shared.companion

/**
 * What the companion host needs from the platform around it, beyond the
 * socket: telling the network it is there, and keeping what a companion sends.
 * Each is optional in effect - a platform that cannot do one leaves it out.
 */

/** DNS-SD: `_indoorbike._tcp`, with the protocol range, path, app and session in TXT. */
interface CompanionAdvertiser {
    /** Advertise on [port]; [onResult] says whether it took. Failing is not fatal. */
    fun start(port: Int, sessionId: String, appVersion: String, onResult: (Boolean) -> Unit)
    fun stop()
}

/** The UDP beacon, for companions with no DNS-SD. Re-reads [payload] every time it sends. */
interface CompanionBeacon {
    fun start(payload: () -> CompanionAnnouncement?)
    fun stop()
}

/** Where a companion's screenshots are kept, with the ride they were taken on. */
fun interface ScreenshotSink {
    fun keep(rideStartEpochMs: Long, image: ScreenshotImage, takenAtEpochMs: Long, rideSeconds: Int)
}

/** Where a ride's companion name and icon are kept, for crediting it on the share card. */
fun interface BrandSink {
    fun keep(rideStartEpochMs: Long, name: String, iconPng: ByteArray?)
}
