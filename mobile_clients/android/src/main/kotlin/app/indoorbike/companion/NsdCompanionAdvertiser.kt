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

import app.indoorbike.shared.companion.CompanionAdvertiser
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import app.indoorbike.shared.companion.CompanionProtocol
import app.indoorbike.shared.util.Log

/**
 * Announces the companion socket on the local network over mDNS / DNS-SD.
 *
 * Uses Android's own [NsdManager] rather than a bundled responder. JmDNS would
 * work too, but it needs `CHANGE_WIFI_MULTICAST_STATE`, a manually held
 * multicast lock, and a few hundred kilobytes of dependency, to do a job the
 * platform already does. Fewer permissions on the manifest is worth more here
 * than control over the responder.
 *
 * **Advertising is a convenience, never a requirement.** Plenty of networks
 * drop multicast - guest Wi-Fi, some mesh routers, most corporate networks -
 * and on those this simply never fires its callback. That is why the pairing
 * screen shows the address and a QR code regardless: discovery failing must
 * cost the rider nothing but a scan.
 *
 * The advertisement is withdrawn when the ride ends. The socket is only open
 * for the duration of a companion ride, and an announcement outliving it would
 * point a game at a phone that is no longer listening.
 */
class NsdCompanionAdvertiser(context: Context) : CompanionAdvertiser {

    private val nsd: NsdManager? =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var listener: NsdManager.RegistrationListener? = null

    /** Name the service ended up with, which the system may have made unique. */
    @Volatile
    var registeredName: String? = null
        private set

    /**
     * Publish `_indoorbike._tcp` on [port].
     *
     * [onResult] reports whether the network accepted it, so the pairing screen
     * can say "discoverable" or fall back to telling the rider to scan.
     */
    override fun start(
        port: Int,
        sessionId: String,
        appVersion: String,
        onResult: (advertising: Boolean) -> Unit,
    ) {
        val manager = nsd
        if (manager == null) {
            Log.w(TAG, "No NSD service on this device; discovery unavailable")
            onResult(false)
            return
        }
        stop()

        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = CompanionProtocol.SERVICE_TYPE
            setPort(port)
            // The TXT records a companion needs before it opens a socket: what
            // this build speaks, and where. Version first, so a client can rule
            // the phone out without connecting at all.
            setAttribute("proto", CompanionProtocol.SUPPORTED.toString())
            setAttribute("path", CompanionProtocol.PATH)
            setAttribute("app", appVersion)
            setAttribute("session", sessionId)
            setAttribute("mode", "companion")
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredName = info.serviceName
                Log.i(TAG, "Advertising as " + info.serviceName + " on " + port)
                onResult(true)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed (" + errorCode + "); QR pairing still works")
                onResult(false)
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registeredName = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed (" + errorCode + ")")
            }
        }

        listener = registration
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onFailure {
                Log.w(TAG, "Could not register mDNS service: " + it.message)
                listener = null
                onResult(false)
            }
    }

    /** Withdraw the advertisement. Safe to call when nothing is registered. */
    override fun stop() {
        val manager = nsd ?: return
        val current = listener ?: return
        listener = null
        registeredName = null
        // Unregistering a listener the framework has already torn down throws
        // IllegalArgumentException rather than returning, and it happens
        // routinely when Wi-Fi drops underneath a running ride.
        runCatching { manager.unregisterService(current) }
            .onFailure { Log.w(TAG, "mDNS unregister ignored: " + it.message) }
    }

    private companion object {
        const val TAG = "CompanionAdvertiser"

        /**
         * What a rider sees in their game's device list. The system appends a
         * suffix if two phones on the same network use it, which is exactly the
         * behaviour wanted: two trainers in a garage should be distinguishable.
         */
        const val SERVICE_NAME = "Indoor Bike"
    }
}
