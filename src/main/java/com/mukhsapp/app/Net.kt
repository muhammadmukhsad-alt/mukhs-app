package com.mukhsapp.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Base64
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
import java.io.File
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

class Net(private val ctx: Context, private val store: Store, var ui: NetListener) {

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
    private val picBuf = HashMap<String, Array<String?>>()
    private val mediaMeta = HashMap<String, JSONObject>()
    private val mediaParts = HashMap<String, Array<String?>>()
    private val statusMeta = HashMap<String, JSONObject>()
    private val statusFiles = HashMap<String, String>()

    var myHash = ""
    var myName = ""
    var myAbout = ""
    var searching = false
    var foreground = true
    var activeChat: String? = null
    var openAfterConnect: String? = null

    // ---------- people ----------

    fun nameOf(h: String): String {
        if (Groups.isId(h)) return store.groupName(h)
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

    // ---------- calls ----------

    fun sendBytes(h: String, b: ByteArray) {
        val ep = endpointOfConnected(h) ?: return
        try {
            client.sendPayload(ep, Payload.fromBytes(b))
        } catch (e: Exception) {
            // ignore
        }
    }

    fun sendSignal(h: String, j: JSONObject) {
        val ep = endpointOfConnected(h) ?: return
        send(ep, j)
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
        try {
            client.sendPayload(ep, Payload.fromBytes(j.toString().toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            // too big or connection gone
        }
    }

    // my name card: about text first, then the picture in small pieces (one message is limited to 32 KB)
    private fun sendProfile(ep: String) {
        send(ep, JSONObject().put("t", "p").put("about", myAbout).put("k", Relay.myPub(ctx)))
        val img = Profile.base64(ctx, "me")
        if (img.isEmpty()) return
        val size = 20000
        val total = (img.length + size - 1) / size
        for (i in 0 until total) {
            val part = img.substring(i * size, minOf(img.length, (i + 1) * size))
            send(ep, JSONObject().put("t", "pc").put("i", i).put("n", total).put("d", part))
        }
    }

    fun broadcastProfile() {
        for (ep in connected.keys) sendProfile(ep)
    }

    // a photo, voice note or document: a small header, then the file in pieces
    private fun sendMediaOut(ep: String, uid: String, ts: Long, kind: String, path: String, name: String, dur: Int, quote: String = "", qmine: Boolean = false, meta: JSONObject? = null) {
        val f = File(path)
        if (!f.exists()) return
        val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        val size = 20000
        val total = (b64.length + size - 1) / size
        val head = JSONObject().put("t", "f").put("id", uid).put("ts", ts).put("k", kind)
            .put("n", name).put("dur", dur).put("c", total)
        if (quote.isNotEmpty()) head.put("q", quote).put("qw", if (qmine) 1 else 0)
        mergeInto(head, meta)
        send(ep, head)
        for (i in 0 until total) {
            val part = b64.substring(i * size, minOf(b64.length, (i + 1) * size))
            send(ep, JSONObject().put("t", "fc").put("id", uid).put("i", i).put("c", total).put("d", part))
        }
    }

    fun sendMedia(h: String, kind: String, path: String, name: String, dur: Int, quote: String = "", qmine: Boolean = false): Msg {
        val uid = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        if (Groups.isId(h)) return fanOut(h, uid, ts, kind, name, path, dur, quote, qmine)
        val ep = endpointOfConnected(h)
        var state = 0
        if (ep != null) {
            sendMediaOut(ep, uid, ts, kind, path, name, dur, quote, qmine)
            state = 1
        } else if (relayOutMedia(h, uid, ts, kind, path, name, dur, quote, qmine, null) == 2) {
            state = 1
        }
        val id = store.insertOut(h, uid, name, ts, state, quote, qmine, kind, path, dur)
        return Msg(id, uid, true, name, ts, state, "", false, quote, qmine, kind, path, dur)
    }

    private fun checkMedia(h: String, ep: String, uid: String) {
        val meta = mediaMeta[uid] ?: return
        val parts = mediaParts[uid] ?: return
        val total = meta.optInt("c")
        if (parts.size != total || parts.any { it == null }) return
        mediaMeta.remove(uid)
        mediaParts.remove(uid)
        send(ep, JSONObject().put("t", "d").put("id", uid))
        if (meta.optString("k") == "si" || meta.optString("k") == "sa") {
            try {
                val nm = meta.optString("n")
                var ex = nm.substringAfterLast('.', "bin").filter { it.isLetterOrDigit() }.take(8)
                if (ex.isEmpty()) ex = "bin"
                val sf = File(Media.dir(ctx), "st_" + uid.replace("~", "_") + "." + ex)
                sf.writeBytes(Base64.decode(parts.joinToString(""), Base64.NO_WRAP))
                statusFiles[uid] = sf.path
                checkStatus(h, uid.substringBeforeLast('~'))
            } catch (e: Exception) {
                // ignore a broken file
            }
            return
        }
        if (store.hasUid(uid)) return
        try {
            val kind = meta.optString("k")
            val name = meta.optString("n")
            val ext0 = if (kind == "a") "m4a" else if (kind == "i") "jpg" else name.substringAfterLast('.', "bin")
            var ext = ext0.filter { it.isLetterOrDigit() }.take(8)
            if (ext.isEmpty()) ext = "bin"
            val f = File(Media.dir(ctx), "in_" + uid + "." + ext)
            f.writeBytes(Base64.decode(parts.joinToString(""), Base64.NO_WRAP))
            val ts = meta.optLong("ts", System.currentTimeMillis())
            val dur = meta.optInt("dur")
            val peer = peerFor(h, meta)
            val viewing = foreground && activeChat == peer
            val mq = meta.optString("q")
            val mqm = meta.optInt("qw") == 1
            val rowId = store.insertIn(peer, uid, name, ts, !viewing, mq, mqm, kind, f.path, dur)
            if (peer != h) store.setSender(uid, h)
            store.touchSeen(h)
            if (viewing) sendReadAck(ep, listOf(uid))
            ui.onIncoming(peer, Msg(rowId, uid, false, name, ts, 0, "", false, mq, mqm, kind, f.path, dur, if (peer != h) h else ""))
        } catch (e: Exception) {
            // ignore a broken file
        }
    }

    // ---------- statuses ----------

    // tell a person which statuses I have now (they ask for the ones they miss)
    private fun announceStatuses(ep: String) {
        val arr = JSONArray()
        for (s in store.statuses("")) arr.put(s.uid)
        send(ep, JSONObject().put("t", "sa").put("ids", arr))
    }

    fun announceAll() {
        for (ep in connected.keys) announceStatuses(ep)
    }

    private fun sendStatus(ep: String, s: Status) {
        val hasImg = s.img.isNotEmpty() && File(s.img).exists()
        val hasAud = s.aud.isNotEmpty() && File(s.aud).exists()
        send(
            ep,
            JSONObject().put("t", "sm").put("id", s.uid).put("ts", s.ts).put("k", s.kind)
                .put("tx", s.text).put("bg", s.bg).put("im", if (hasImg) 1 else 0).put("au", if (hasAud) 1 else 0)
        )
        if (hasImg) sendMediaOut(ep, s.uid + "~i", s.ts, "si", s.img, "s." + File(s.img).extension, 0)
        if (hasAud) sendMediaOut(ep, s.uid + "~a", s.ts, "sa", s.aud, "s." + File(s.aud).extension, 0)
    }

    // I watched a status: tell its owner (or keep it for when we meet)
    fun sendStatusView(h: String, uid: String) {
        val ep = endpointOfConnected(h) ?: return
        send(ep, JSONObject().put("t", "sv").put("id", uid))
        store.setViewSent(uid)
    }

    private fun checkStatus(h: String, uid: String) {
        val meta = statusMeta[uid] ?: return
        val img = statusFiles[uid + "~i"]
        val aud = statusFiles[uid + "~a"]
        if (meta.optInt("im") == 1 && img == null) return
        if (meta.optInt("au") == 1 && aud == null) return
        statusMeta.remove(uid)
        statusFiles.remove(uid + "~i")
        statusFiles.remove(uid + "~a")
        if (store.hasStatus(uid)) return
        store.addStatus(
            Status(
                0, h, uid, meta.optString("k", "t"), meta.optString("tx"), meta.optInt("bg"),
                img ?: "", aud ?: "", meta.optLong("ts", System.currentTimeMillis()), false
            )
        )
        ui.onChanged()
    }

    fun sendReaction(h: String, uid: String, e: String) {
        val ep = endpointOfConnected(h) ?: return
        send(ep, JSONObject().put("t", "x").put("id", uid).put("e", e))
    }

    // after my name changes, advertise the new name
    fun restartAdvertising() {
        if (!searching) return
        client.stopAdvertising()
        client.startAdvertising(
            advName(), SERVICE_ID, connCallback,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        )
    }

    private fun msgJson(uid: String, ts: Long, body: String, quote: String = "", qmine: Boolean = false, meta: JSONObject? = null): JSONObject {
        val j = JSONObject().put("t", "m").put("id", uid).put("ts", ts).put("b", body)
        if (quote.isNotEmpty()) j.put("q", quote).put("qw", if (qmine) 1 else 0)
        mergeInto(j, meta)
        return j
    }

    private fun mergeInto(to: JSONObject, from: JSONObject?) {
        if (from == null) return
        val names = from.keys()
        while (names.hasNext()) {
            val k = names.next()
            to.put(k, from.get(k))
        }
    }

    private fun sendReadAck(ep: String, uids: List<String>) {
        val arr = JSONArray()
        for (u in uids) arr.put(u)
        send(ep, JSONObject().put("t", "r").put("ids", arr))
    }

    fun sendText(h: String, text: String, quote: String = "", qmine: Boolean = false): Msg {
        val uid = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        if (Groups.isId(h)) return fanOut(h, uid, ts, "t", text, "", 0, quote, qmine)
        val ep = endpointOfConnected(h)
        var state = 0
        if (ep != null) {
            send(ep, msgJson(uid, ts, text, quote, qmine))
            state = 1
        } else if (relayOut(h, uid, ts, text, quote, qmine, null) == 2) {
            state = 1
        }
        val id = store.insertOut(h, uid, text, ts, state, quote, qmine)
        return Msg(id, uid, true, text, ts, state, "", false, quote, qmine)
    }

    // ---------- store-and-forward (relay) ----------

    private fun relayJson(uid: String, dst: String, ts: Long, hops: Int, blob: String): JSONObject =
        JSONObject().put("t", "re").put("id", uid).put("dst", dst).put("ts", ts).put("hp", hops).put("b", blob)

    // one envelope to one phone: in one piece if it is small, else in pieces ("rc")
    private fun sendEnvelope(ep: String, uid: String, dst: String, ts: Long, hops: Int, blob: String) {
        if (blob.length <= 22000) {
            send(ep, relayJson(uid, dst, ts, hops, blob))
            return
        }
        val total = (blob.length + Relay.PIECE - 1) / Relay.PIECE
        for (i in 0 until total) {
            val part = blob.substring(i * Relay.PIECE, minOf(blob.length, (i + 1) * Relay.PIECE))
            send(
                ep,
                JSONObject().put("t", "rc").put("id", uid).put("dst", dst).put("ts", ts).put("hp", hops)
                    .put("i", i).put("n", total).put("d", part)
            )
        }
    }

    // 0 = could not (no key for that person), 1 = sealed and kept (goes out when I meet anybody),
    // 2 = sealed, kept and handed to somebody I am connected to right now
    private fun relayStore(h: String, uid: String, ts: Long, inner: JSONObject, meta: JSONObject?): Int {
        val pub = store.pub(h)
        if (pub.isEmpty()) return 0
        mergeInto(inner, meta)
        val blob = Relay.seal(ctx, pub, h, myHash, uid, ts, inner) ?: return 0
        store.putRelay(uid, h, ts, 0, blob)
        var handed = false
        for (cep in connected.keys) {
            sendEnvelope(cep, uid, h, ts, 1, blob)
            handed = true
        }
        return if (handed) 2 else 1
    }

    // The person is not near me. Seal the message for them, keep it, and hand it to everyone
    // I am connected to now; it also goes to whoever I meet later.
    private fun relayOut(h: String, uid: String, ts: Long, text: String, quote: String, qmine: Boolean, meta: JSONObject?): Int {
        val inner = JSONObject().put("k", "t").put("b", text)
        if (quote.isNotEmpty()) inner.put("q", quote).put("qw", if (qmine) 1 else 0)
        return relayStore(h, uid, ts, inner, meta)
    }

    // voice notes, photos and small documents travel the same way (size limited)
    private fun relayOutMedia(h: String, uid: String, ts: Long, kind: String, path: String, name: String, dur: Int, quote: String, qmine: Boolean, meta: JSONObject?): Int {
        if (store.pub(h).isEmpty()) return 0
        val f = File(path)
        if (!f.exists() || f.length() > Relay.MAX_FILE) return 0
        val inner = JSONObject().put("k", kind).put("n", name).put("dur", dur)
            .put("d", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        if (quote.isNotEmpty()) inner.put("q", quote).put("qw", if (qmine) 1 else 0)
        return relayStore(h, uid, ts, inner, meta)
    }

    // when two phones meet: say what I carry and what I know is already delivered
    private fun sendRelayHello(ep: String) {
        val have = JSONArray()
        for (u in store.relayIds()) have.put(u)
        val done = JSONArray()
        for (u in store.relayDoneIds()) done.put(u)
        send(ep, JSONObject().put("t", "rh").put("have", have).put("done", done))
    }

    private fun applyDone(uid: String) {
        if (uid.isEmpty()) return
        store.relayDone(uid, System.currentTimeMillis())
        store.setState(uid, 2)
    }

    // pieces of a big envelope that are still arriving: id -> pieces
    private val relayParts = HashMap<String, Array<String?>>()

    private fun acceptEnvelope(h: String, ep: String, uid: String, dst: String, ts: Long, hops: Int, blob: String) {
        if (uid.isEmpty() || dst.isEmpty() || blob.isEmpty() || blob.length > Relay.MAX_BLOB) return
        if (ts + Relay.TTL_MS < System.currentTimeMillis()) return
        if (store.hasUid(uid)) {
            if (dst == myHash) send(ep, JSONObject().put("t", "ra").put("id", uid))
            return
        }
        if (store.hasRelay(uid)) return
        if (dst == myHash) deliverRelayed(ep, uid, blob)
        else if (hops <= Relay.MAX_HOPS) store.putRelay(uid, dst, ts, hops, blob)
        ui.onChanged()
    }

    private fun mediaExt(kind: String, name: String): String {
        val ext0 = if (kind == "a") "m4a" else if (kind == "i") "jpg" else name.substringAfterLast('.', "bin")
        val ext = ext0.filter { it.isLetterOrDigit() }.take(8)
        return if (ext.isEmpty()) "bin" else ext
    }

    // an envelope for me: open it, check who signed it, save it as a normal message
    private fun deliverRelayed(ep: String, uid: String, blob: String) {
        val o = Relay.open(ctx, uid, myHash, blob) ?: return
        val src = o.src
        if (src.length != 12 || src == myHash || !src.all { it in '0'..'9' || it in 'a'..'f' }) return
        val known = store.pub(src)
        if (known.isNotEmpty() && known != o.senderPub) return
        val inner = o.inner
        val kind = inner.optString("k", "t")
        if (kind != "t" && kind != "a" && kind != "i" && kind != "d") return
        store.savePeer(src, nameOf(src))
        if (known.isEmpty()) store.setPub(src, o.senderPub)
        val peer = peerFor(src, inner)
        val quote = inner.optString("q")
        val qm = inner.optInt("qw", 0) == 0
        val viewing = foreground && activeChat == peer
        val rowId: Long
        val msg: Msg
        if (kind == "t") {
            val body = inner.optString("b")
            rowId = store.insertIn(peer, uid, body, o.ts, !viewing, quote, qm)
            msg = Msg(rowId, uid, false, body, o.ts, 0, "", false, quote, qm, "t", "", 0, if (peer != src) src else "")
        } else {
            val name = inner.optString("n")
            val dur = inner.optInt("dur")
            val data = inner.optString("d")
            if (data.isEmpty()) return
            val f = File(Media.dir(ctx), "in_" + uid + "." + mediaExt(kind, name))
            try {
                f.writeBytes(Base64.decode(data, Base64.NO_WRAP))
            } catch (e: Exception) {
                return
            }
            rowId = store.insertIn(peer, uid, name, o.ts, !viewing, quote, qm, kind, f.path, dur)
            msg = Msg(rowId, uid, false, name, o.ts, 0, "", false, quote, qm, kind, f.path, dur, if (peer != src) src else "")
        }
        if (peer != src) store.setSender(uid, src)
        store.relayDone(uid, o.ts)
        send(ep, JSONObject().put("t", "ra").put("id", uid))
        ui.onIncoming(peer, msg)
    }

    // ---------- groups and broadcast lists ----------

    // what every group message carries, so a phone that has never seen the group can build it
    private fun groupMeta(g: Grp?): JSONObject? {
        if (g == null || g.kind != "g") return null
        return JSONObject().put("g", g.gid).put("gn", g.name).put("gm", g.members.joinToString(",")).put("ga", g.admin)
    }

    // which chat does a message belong to: the group (if it says so and I am in it) or the person
    private fun peerFor(src: String, j: JSONObject): String {
        val gid = j.optString("g")
        if (gid.isEmpty() || !Groups.isGroupId(gid) || gid.length > 30) return src
        val members = j.optString("gm").split(",").filter { it.length == 12 }.take(100)
        if (!members.contains(src) || !members.contains(myHash)) return src
        val old = store.group(gid)
        if (old == null) {
            store.putGroup(gid, j.optString("gn").take(40), members, src, "g")
        } else if (old.admin == src) {
            store.putGroup(gid, j.optString("gn").take(40), members, old.admin, "g")
        } else if (!old.members.contains(src)) {
            return src
        }
        return gid
    }

    // one message to everybody in a group or broadcast list: each person gets it directly if connected,
    // else sealed through the relay, else it waits until we meet
    private fun fanOut(gid: String, uid: String, ts: Long, kind: String, body: String, path: String, dur: Int, quote: String, qmine: Boolean): Msg {
        val g = store.group(gid)
        val meta = groupMeta(g)
        var any = false
        if (g != null) {
            for (m in g.members) {
                if (m == myHash) continue
                val ep = endpointOfConnected(m)
                if (ep != null) {
                    if (kind == "t") send(ep, msgJson(uid, ts, body, quote, qmine, meta))
                    else sendMediaOut(ep, uid, ts, kind, path, body, dur, quote, qmine, meta)
                    any = true
                } else {
                    val r = if (kind == "t") relayOut(m, uid, ts, body, quote, qmine, meta)
                    else relayOutMedia(m, uid, ts, kind, path, body, dur, quote, qmine, meta)
                    if (r == 2) any = true
                    if (r == 0) store.gqAdd(uid, m)
                }
            }
        }
        val state = if (any) 1 else 0
        val id = store.insertOut(gid, uid, body, ts, state, quote, qmine, kind, path, dur)
        return Msg(id, uid, true, body, ts, state, "", false, quote, qmine, kind, path, dur)
    }

    // when we meet again: send what was waiting, and the "read" receipts that were waiting
    private fun flush(h: String) {
        val ep = endpointOfConnected(h) ?: return
        for (m in store.pendingOut(h)) {
            if (m.kind == "t") send(ep, msgJson(m.uid, m.ts, m.body, m.quote, m.qmine))
            else sendMediaOut(ep, m.uid, m.ts, m.kind, m.path, m.body, m.dur, m.quote, m.qmine)
            store.setState(m.uid, 1)
        }
        for (p in store.gqFor(h)) {
            val m = store.msgByUid(p.first) ?: continue
            val meta = groupMeta(store.group(p.second))
            if (m.kind == "t") send(ep, msgJson(m.uid, m.ts, m.body, m.quote, m.qmine, meta))
            else sendMediaOut(ep, m.uid, m.ts, m.kind, m.path, m.body, m.dur, m.quote, m.qmine, meta)
            store.gqDone(p.first, h)
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
        if (type == "cv" || type == "cy" || type == "cn") {
            Calls.onSignal(h, j)
            return
        }
        if (type == "m") {
            val uid = j.optString("id")
            val body = j.optString("b")
            val ts = j.optLong("ts", System.currentTimeMillis())
            if (uid.isEmpty()) return
            // tell the sender it arrived
            send(ep, JSONObject().put("t", "d").put("id", uid))
            if (store.hasUid(uid)) return
            val peer = peerFor(h, j)
            val viewing = foreground && activeChat == peer
            val quote = j.optString("q")
            val qm = j.optInt("qw", 0) == 0
            val rowId = store.insertIn(peer, uid, body, ts, !viewing, quote, qm)
            if (peer != h) store.setSender(uid, h)
            store.touchSeen(h)
            if (viewing) sendReadAck(ep, listOf(uid))
            ui.onIncoming(peer, Msg(rowId, uid, false, body, ts, 0, "", false, quote, qm, "t", "", 0, if (peer != h) h else ""))
        } else if (type == "p") {
            store.setAbout(h, j.optString("about"))
            val key = j.optString("k")
            if (key.length in 80..400) store.setPub(h, key)
            val img = j.optString("img")
            if (img.isNotEmpty()) Profile.saveBase64(ctx, h, img)
            ui.onChanged()
        } else if (type == "pc") {
            val i = j.optInt("i")
            val total = j.optInt("n")
            if (total in 1..12 && i in 0 until total) {
                var buf = picBuf[h]
                if (i == 0 || buf == null || buf.size != total) buf = arrayOfNulls<String>(total)
                buf[i] = j.optString("d")
                picBuf[h] = buf
                if (buf.all { it != null }) {
                    Profile.saveBase64(ctx, h, buf.joinToString(""))
                    picBuf.remove(h)
                    ui.onChanged()
                }
            }
        } else if (type == "sa") {
            val a = j.optJSONArray("ids")
            val ids = ArrayList<String>()
            if (a != null) for (i in 0 until a.length()) ids.add(a.optString(i))
            store.keepOnlyStatuses(h, ids)
            val missing = JSONArray()
            for (u in ids) if (!store.hasStatus(u)) missing.put(u)
            if (missing.length() > 0) send(ep, JSONObject().put("t", "sq").put("ids", missing))
            ui.onChanged()
        } else if (type == "sq") {
            val a = j.optJSONArray("ids")
            if (a != null) {
                val mine = store.statuses("")
                for (i in 0 until a.length()) {
                    val u = a.optString(i)
                    for (s in mine) if (s.uid == u) sendStatus(ep, s)
                }
            }
        } else if (type == "sm") {
            val uid = j.optString("id")
            if (uid.isNotEmpty() && !store.hasStatus(uid)) {
                statusMeta[uid] = j
                checkStatus(h, uid)
            }
        } else if (type == "sv") {
            val uid = j.optString("id")
            if (uid.isNotEmpty()) {
                store.addView(uid, h, System.currentTimeMillis())
                ui.onChanged()
            }
        } else if (type == "f") {
            val uid = j.optString("id")
            val c = j.optInt("c")
            if (uid.isNotEmpty() && c in 1..700) {
                mediaMeta[uid] = j
                checkMedia(h, ep, uid)
            }
        } else if (type == "fc") {
            val uid = j.optString("id")
            val i = j.optInt("i")
            val c = j.optInt("c")
            if (uid.isNotEmpty() && c in 1..700 && i in 0 until c) {
                var buf: Array<String?>? = mediaParts[uid]
                if (buf == null || buf.size != c) {
                    buf = arrayOfNulls<String>(c)
                    mediaParts[uid] = buf
                }
                buf[i] = j.optString("d")
                checkMedia(h, ep, uid)
            }
        } else if (type == "x") {
            store.setReact(j.optString("id"), j.optString("e"))
            ui.onChanged()
        } else if (type == "rh") {
            val dn = j.optJSONArray("done")
            if (dn != null) for (i in 0 until dn.length()) applyDone(dn.optString(i))
            val have = HashSet<String>()
            val ha = j.optJSONArray("have")
            if (ha != null) for (i in 0 until ha.length()) have.add(ha.optString(i))
            for (u in store.relayIds()) {
                if (have.contains(u)) continue
                val e = store.relayGet(u) ?: continue
                if (e.hops >= Relay.MAX_HOPS && e.dst != h) continue
                sendEnvelope(ep, e.uid, e.dst, e.ts, e.hops + 1, e.blob)
                store.setState(e.uid, 1)
            }
            ui.onChanged()
        } else if (type == "re") {
            acceptEnvelope(h, ep, j.optString("id"), j.optString("dst"), j.optLong("ts", 0L), j.optInt("hp", 1), j.optString("b"))
        } else if (type == "rc") {
            val uid = j.optString("id")
            val i = j.optInt("i")
            val n = j.optInt("n")
            if (uid.isEmpty() || n !in 2..80 || i !in 0 until n) return
            if (store.hasUid(uid) || store.hasRelay(uid)) {
                if (j.optString("dst") == myHash && store.hasUid(uid)) send(ep, JSONObject().put("t", "ra").put("id", uid))
                return
            }
            var buf = relayParts[uid]
            if (buf == null || buf.size != n) {
                if (relayParts.size > 6) relayParts.clear()
                buf = arrayOfNulls<String>(n)
                relayParts[uid] = buf
            }
            buf[i] = j.optString("d")
            if (buf.all { it != null }) {
                relayParts.remove(uid)
                acceptEnvelope(h, ep, uid, j.optString("dst"), j.optLong("ts", 0L), j.optInt("hp", 1), buf.joinToString(""))
            }
        } else if (type == "ra") {
            applyDone(j.optString("id"))
            ui.onChanged()
        } else if (type == "d") {
            val did = j.optString("id")
            if (store.hasRelay(did)) store.relayDone(did, System.currentTimeMillis())
            store.setState(did, 2)
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
            val h = connected[endpointId] ?: pendingConn[endpointId]?.first ?: return
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            // call sound and video frames start with a small number, messages start with {
            if (bytes.size > 1 && bytes[0].toInt() in 1..9) {
                Calls.onFrame(h, bytes)
                return
            }
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
            autoConnect(endpointId, h)
        }

        override fun onEndpointLost(endpointId: String) {
            val h = nearby.remove(endpointId)
            if (h != null) store.touchSeen(h)
            ui.onChanged()
        }
    }

    // Everyone nearby who is in my contacts is connected automatically.
    // Only one of the two phones asks first (the smaller code), the other waits a bit,
    // so both do not ask at the same moment.
    private fun autoConnect(endpointId: String, h: String, tries: Int = 0) {
        if (!autoOn) return
        val wait = if (myHash < h) 400L else 5000L
        handler.postDelayed({
            if (nearby[endpointId] == h && !isConnected(h) && pendingConn.values.none { it.first == h }) {
                client.requestConnection(advName(), endpointId, connCallback)
                    .addOnFailureListener {
                        if (tries < 4) {
                            handler.postDelayed({ autoConnect(endpointId, h, tries + 1) }, 4000L)
                        }
                    }
            }
        }, wait)
    }

    var autoOn = true

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
                sendRelayHello(endpointId)
                sendProfile(endpointId)
                announceStatuses(endpointId)
                for (u in store.unsentViews(h)) sendStatusView(h, u)
                handler.postDelayed({
                    if (connected.containsKey(endpointId)) {
                        sendProfile(endpointId)
                        announceStatuses(endpointId)
                    }
                }, 2500)
                if (openAfterConnect == h) {
                    openAfterConnect = null
                    ui.onOpenChat(h)
                } else {
                    ui.onChanged()
                }
            } else {
                if (openAfterConnect != null) {
                    openAfterConnect = null
                    ui.onNotice(L.s("Haɗawa ta kasa. Gwada kuma.", "Connection failed. Try again."))
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            val h = connected.remove(endpointId)
            relayParts.clear()
            nearby.remove(endpointId)
            if (h != null) {
                store.touchSeen(h)
                Calls.onPeerLost(h)
            }
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
