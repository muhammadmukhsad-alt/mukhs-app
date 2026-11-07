package com.mukhsapp.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

interface NetListener {
    fun onChanged()
    fun onIncoming(peer: String, msg: Msg)
    fun onOpenChat(peer: String)
    fun onNotice(text: String)
}

object Num {
    // Turns 0803 123 4567 or +234 803 123 4567 into 2348031234567
    fun normalize(raw: String): String? {
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.substring(2)
        if (d.startsWith("0") && d.length == 11) {
            d = "234" + d.substring(1)
        } else if (d.length == 10) {
            d = "234$d"
        }
        return if (d.length in 11..15) d else null
    }

    // People nearby only see this short code, never the phone number itself.
    fun hashOf(number: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(("mukhs:$number").toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (i in 0 until 6) sb.append(String.format("%02x", bytes[i]))
        return sb.toString()
    }
}

class Net(private val ctx: Context, private val store: Store, private val ui: NetListener) {

    private val SERVICE_ID = "com.mukhsapp.app.v1"
    private val client: ConnectionsClient = Nearby.getConnectionsClient(ctx)
    private val handler = Handler(Looper.getMainLooper())

    // phone contacts: hash -> (name, number)
    val contacts = HashMap<String, Pair<String, String>>()
    // people nearby (in my contacts): endpointId -> hash
    val nearby = LinkedHashMap<String, String>()
    // people I am connected to: endpointId -> hash
    val connected = HashMap<String, String>()
    private val pendingConn = HashMap<String, Pair<String, String>>()

    var myHash = ""
    var myName = ""
    var searching = false
    var foreground = true
    var activeChat: String? = null
    var openAfterConnect: String? = null

    // ---------- people ----------

    fun nameOf(h: String): String {
        val c = contacts[h]
        return if (c != null) c.first else store.peerName(h)
    }

    fun endpointOfConnected(h: String): String? =
        connected.entries.firstOrNull { it.value == h }?.key

    fun endpointOfNearby(h: String): String? =
        nearby.entries.firstOrNull { it.value == h }?.key

    fun isConnected(h: String): Boolean = endpointOfConnected(h) != null

    fun isNearby(h: String): Boolean = isConnected(h) || endpointOfNearby(h) != null

    fun loadContacts() {
        Thread {
            val map = HashMap<String, Pair<String, String>>()
            try {
                val cur = ctx.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null, null
                )
                if (cur != null) {
                    while (cur.moveToNext()) {
                        val num = Num.normalize(cur.getString(1) ?: "")
                        if (num != null) {
                            map[Num.hashOf(num)] = Pair(cur.getString(0) ?: num, num)
                        }
                    }
                    cur.close()
                }
            } catch (e: Exception) {
                // ignore
            }
            handler.post {
                contacts.clear()
                contacts.putAll(map)
                ui.onChanged()
            }
        }.start()
    }

    // ---------- searching ----------

    private fun advName(): String = "$myHash|$myName"

    fun start() {
        if (searching) return
        nearby.clear()
        val adv = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val dis = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(advName(), SERVICE_ID, connCallback, adv)
            .addOnFailureListener { ui.onNotice("Advertise: ${it.message}") }
        client.startDiscovery(SERVICE_ID, discCallback, dis)
            .addOnFailureListener { ui.onNotice("Discover: ${it.message}") }
        searching = true
        ui.onChanged()
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        searching = false
        nearby.clear()
        ui.onChanged()
    }

    fun stopAll() {
        client.stopAllEndpoints()
    }

    fun connect(h: String) {
        val ep = endpointOfNearby(h) ?: return
        openAfterConnect = h
        client.requestConnection(advName(), ep, connCallback)
            .addOnFailureListener {
                openAfterConnect = null
                ui.onNotice("Connection failed: ${it.message}")
            }
    }

    // ---------- sending ----------

    private fun send(ep: String, j: JSONObject) {
        client.sendPayload(ep, Payload.fromBytes(j.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun msgJson(uid: String, ts: Long, body: String): JSONObject =
        JSONObject().put("t", "m").put("id", uid).put("ts", ts).put("b", body)

    private fun sendReadAck(ep: String, uids: List<String>) {
        val arr = JSONArray()
        for (u in uids) arr.put(u)
        send(ep, JSONObject().put("t", "r").put("ids", arr))
    }

    fun sendText(h: String, text: String): Msg {
        val uid = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        val ep = endpointOfConnected(h)
        var state = 0
        if (ep != null) {
            send(ep, msgJson(uid, ts, text))
            state = 1
        }
        val id = store.insertOut(h, uid, text, ts, state)
        return Msg(id, uid, true, text, ts, state)
    }

    // when we meet again: send what was waiting, and the "read" receipts that were waiting
    private fun flush(h: String) {
        val ep = endpointOfConnected(h) ?: return
        for (m in store.pendingOut(h)) {
            send(ep, msgJson(m.uid, m.ts, m.body))
            store.setState(m.uid, 1)
        }
        val reads = store.unackedReads(h)
        if (reads.isNotEmpty()) {
            sendReadAck(ep, reads)
            store.setRacked(reads)
        }
    }

    // called when I open a chat
    fun markChatRead(h: String) {
        val uids = store.markRead(h)
        if (uids.isEmpty()) return
        val ep = endpointOfConnected(h)
        if (ep != null) {
            sendReadAck(ep, uids)
            store.setRacked(uids)
        }
    }

    // ---------- receiving ----------

    private fun handle(h: String, ep: String, j: JSONObject) {
        val type = j.optString("t")
        if (type == "m") {
            val uid = j.optString("id")
            val body = j.optString("b")
            val ts = j.optLong("ts", System.currentTimeMillis())
            if (uid.isEmpty()) return
            // tell the sender it arrived
            send(ep, JSONObject().put("t", "d").put("id", uid))
            if (store.hasUid(uid)) return
            val viewing = foreground && activeChat == h
            val rowId = store.insertIn(h, uid, body, ts, !viewing)
            store.touchSeen(h)
            if (viewing) sendReadAck(ep, listOf(uid))
            ui.onIncoming(h, Msg(rowId, uid, false, body, ts, 0))
        } else if (type == "d") {
            store.setState(j.optString("id"), 2)
            ui.onChanged()
        } else if (type == "r") {
            val a = j.optJSONArray("ids")
            if (a != null) {
                for (i in 0 until a.length()) store.setState(a.optString(i), 3)
            }
            ui.onChanged()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val h = connected[endpointId] ?: return
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            try {
                handle(h, endpointId, JSONObject(String(bytes, Charsets.UTF_8)))
            } catch (e: Exception) {
                // ignore messages we do not understand
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    // ---------- discovery and connection callbacks ----------

    private fun parseAdv(name: String): Pair<String, String>? {
        val i = name.indexOf('|')
        if (i <= 0) return null
        return Pair(name.substring(0, i), name.substring(i + 1))
    }

    private val discCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val p = parseAdv(info.endpointName) ?: return
            val h = p.first
            if (h == myHash) return
            // Only people whose number is in MY contacts are shown.
            if (!contacts.containsKey(h)) return
            nearby[endpointId] = h
            store.savePeer(h, p.second)
            store.touchSeen(h)
            ui.onChanged()
        }

        override fun onEndpointLost(endpointId: String) {
            val h = nearby.remove(endpointId)
            if (h != null) store.touchSeen(h)
            ui.onChanged()
        }
    }

    private val connCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val p = parseAdv(info.endpointName)
            if (p == null) {
                client.rejectConnection(endpointId)
                return
            }
            pendingConn[endpointId] = p
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val p = pendingConn.remove(endpointId)
            if (result.status.isSuccess && p != null) {
                val h = p.first
                connected[endpointId] = h
                store.savePeer(h, p.second)
                store.touchSeen(h)
                flush(h)
                if (openAfterConnect == h) {
                    openAfterConnect = null
                    ui.onOpenChat(h)
                } else {
                    ui.onChanged()
                }
            } else {
                openAfterConnect = null
                ui.onNotice(L.s("Haɗawa ta kasa. Gwada kuma.", "Connection failed. Try again."))
            }
        }

        override fun onDisconnected(endpointId: String) {
            val h = connected.remove(endpointId)
            nearby.remove(endpointId)
            if (h != null) store.touchSeen(h)
            ui.onChanged()
            if (searching) {
                // look again so the person can be found and reconnected
                client.stopDiscovery()
                client.startDiscovery(
                    SERVICE_ID,
                    discCallback,
                    DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
                )
            }
        }
    }
}
