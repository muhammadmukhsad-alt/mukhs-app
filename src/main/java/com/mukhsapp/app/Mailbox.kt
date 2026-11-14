package com.mukhsapp.app

import android.content.Context
import android.net.ConnectivityManager
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// A mailbox that lives in a GitHub repository (no Firebase, no Supabase, no server of ours).
//
//   keys/<hash>.json            who is on MukhsApp: public key + name   (so anyone can write to a phone number)
//   box/<hash>/<uid>.json       sealed messages waiting for that person (end-to-end encrypted, GitHub cannot read them)
//   ack/<hash>/<uid>.txt        "it arrived" receipts for the sender
//
// Send: the message is sealed with the receiver's public key and uploaded at once. The receiver's phone
// looks in its own box when the app is open (or the background service is running), saves the message,
// answers with an ack, and removes the file. It needs an internet connection, but only a few kilobytes.
//
// The repository and the access token come from the build (GitHub Actions secrets MAILBOX_REPO / MAILBOX_TOKEN).
// With no token this whole class stays off and the app works as before (Bluetooth / Wi-Fi Direct).
object Mailbox {

    private val repo: String = BuildConfig.MAILBOX_REPO
    private val token: String = BuildConfig.MAILBOX_TOKEN
    val enabled: Boolean get() = token.isNotEmpty()

    private lateinit var ctx: Context
    private lateinit var store: Store
    private lateinit var net: Net
    private var thread: Thread? = null
    private val wake = LinkedBlockingQueue<Int>()
    private val etags = HashMap<String, String>()
    private val missing = HashMap<String, Long>()

    fun start(c: Context, s: Store, n: Net) {
        if (!enabled || thread != null) return
        ctx = c.applicationContext
        store = s
        net = n
        val t = Thread {
            while (true) {
                try {
                    if (online() && net.myHash.isNotEmpty()) cycle()
                } catch (e: Exception) {
                    // try again next time
                }
                try {
                    wake.poll(if (net.foreground) 15L else 90L, TimeUnit.SECONDS)
                    wake.clear()
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }
        t.isDaemon = true
        t.start()
        thread = t
    }

    // something new to send: do it now
    fun kick() {
        if (enabled) wake.offer(1)
    }

    @Suppress("DEPRECATION")
    private fun online(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.activeNetworkInfo?.isConnected == true
    }

    private fun cycle() {
        publishKey()
        pump()
        pollInbox()
        pollAcks()
    }

    // ---------- GitHub contents API ----------

    private class Res(val code: Int, val body: ByteArray, val etag: String)

    private fun call(method: String, path: String, body: String?, accept: String = "application/vnd.github+json", etag: String? = null): Res {
        val c = URL("https://api.github.com/repos/" + repo + "/contents/" + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 40000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", accept)
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", "MukhsApp")
            if (etag != null && etag.isNotEmpty()) c.setRequestProperty("If-None-Match", etag)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = if (stream != null) stream.use { it.readBytes() } else ByteArray(0)
            return Res(code, bytes, c.getHeaderField("ETag") ?: "")
        } finally {
            c.disconnect()
        }
    }

    private fun shaOf(path: String): String? {
        val r = call("GET", path, null)
        if (r.code != 200) return null
        return try {
            JSONObject(String(r.body, Charsets.UTF_8)).optString("sha").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    // create a file (or replace it if overwrite). true = it is there now
    private fun put(path: String, data: ByteArray, overwrite: Boolean): Boolean {
        var sha: String? = null
        for (attempt in 0 until 3) {
            val b = JSONObject().put("message", "m").put("content", Base64.encodeToString(data, Base64.NO_WRAP))
            if (sha != null) b.put("sha", sha)
            val r = call("PUT", path, b.toString())
            if (r.code == 200 || r.code == 201) return true
            if (r.code == 422 && sha == null) {
                // the file already exists
                if (!overwrite) return true
                sha = shaOf(path) ?: return false
                continue
            }
            if (r.code == 409 || r.code >= 500) {
                Thread.sleep(1500L * (attempt + 1))
                continue
            }
            return false
        }
        return false
    }

    private fun delete(path: String, sha: String) {
        try {
            val b = JSONObject().put("message", "d").put("sha", sha)
            call("DELETE", path, b.toString())
        } catch (e: Exception) {
            // the file stays; we already have the message, so it is ignored next time
        }
    }

    private fun get(path: String): ByteArray? {
        val r = call("GET", path, null, "application/vnd.github.raw+json")
        return if (r.code == 200) r.body else null
    }

    // ---------- my public key (so people can write to my phone number) ----------

    private fun publishKey() {
        val pub = Relay.myPub(ctx)
        val mark = net.myHash + ":" + pub.hashCode() + ":" + net.myName.hashCode()
        val sp = ctx.getSharedPreferences("mukhs_mb", Context.MODE_PRIVATE)
        if (sp.getString("pub", "") == mark) return
        val j = JSONObject().put("pub", pub).put("name", net.myName)
        if (!put("keys/" + net.myHash + ".json", j.toString().toByteArray(Charsets.UTF_8), true)) return
        // empty folders do not exist in git: a small file makes my box and ack folders exist
        put("box/" + net.myHash + "/.keep", "keep".toByteArray(), false)
        put("ack/" + net.myHash + "/.keep", "keep".toByteArray(), false)
        sp.edit().putString("pub", mark).apply()
    }

    // learn somebody's public key and name from the mailbox
    private fun ensurePub(h: String): Boolean {
        if (store.pub(h).isNotEmpty()) return true
        val t = missing[h]
        if (t != null && System.currentTimeMillis() - t < 300000L) return false
        val b = get("keys/$h.json")
        if (b == null) {
            missing[h] = System.currentTimeMillis()
            return false
        }
        try {
            val j = JSONObject(String(b, Charsets.UTF_8))
            val pub = j.optString("pub")
            if (pub.length !in 80..400) return false
            if (store.peerName(h) == "Friend" && j.optString("name").isNotBlank()) store.savePeer(h, j.optString("name").take(40))
            store.setPub(h, pub)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    // ---------- sending ----------

    private fun upload(h: String, uid: String, ts: Long, blob: String): Boolean {
        val j = JSONObject().put("id", uid).put("dst", h).put("ts", ts).put("b", blob)
        return put("box/$h/$uid.json", j.toString().toByteArray(Charsets.UTF_8), false)
    }

    // everything still waiting: seal it for the receiver and put it in their box
    private fun pump() {
        for (p in store.pendingNet()) {
            val h = p.first
            val m = p.second
            if (net.isConnected(h)) continue
            if (!ensurePub(h)) continue
            val blob = net.sealFor(h, m, null) ?: continue
            if (upload(h, m.uid, m.ts, blob)) net.mailboxSent(m.uid)
        }
        for (q in store.gqAll()) {
            val uid = q.first
            val member = q.second
            val gid = q.third
            if (net.isConnected(member)) continue
            val m = store.msgByUid(uid) ?: continue
            if (!ensurePub(member)) continue
            val blob = net.sealFor(member, m, gid) ?: continue
            if (upload(member, uid, m.ts, blob)) {
                store.gqDone(uid, member)
                net.mailboxSent(uid)
            }
        }
    }

    // ---------- receiving ----------

    // list a folder; null = nothing changed since last time (free: GitHub does not count these)
    private fun list(dir: String): JSONArray? {
        val r = call("GET", dir, null, "application/vnd.github+json", etags[dir])
        if (r.code != 200) return null
        etags[dir] = r.etag
        return try {
            JSONArray(String(r.body, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun pollInbox() {
        val dir = "box/" + net.myHash
        val arr = list(dir) ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            val sha = o.optString("sha")
            if (!name.endsWith(".json")) continue
            val uid = name.removeSuffix(".json")
            if (store.hasUid(uid)) {
                delete("$dir/$name", sha)
                etags.remove(dir)
                continue
            }
            val b = get("$dir/$name") ?: continue
            val j = try {
                JSONObject(String(b, Charsets.UTF_8))
            } catch (e: Exception) {
                delete("$dir/$name", sha)
                etags.remove(dir)
                continue
            }
            net.deliverFromMailbox(uid, j.optString("dst"), j.optLong("ts", 0L), j.optString("b")) { src ->
                Thread {
                    try {
                        if (src != null) {
                            put("ack/$src/$uid.txt", "1".toByteArray(), false)
                            ensurePub(src)
                        }
                        delete("$dir/$name", sha)
                        etags.remove(dir)
                    } catch (e: Exception) {
                        // ignore
                    }
                }.start()
            }
        }
    }

    // receipts: "your message arrived"
    private fun pollAcks() {
        val dir = "ack/" + net.myHash
        val arr = list(dir) ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (!name.endsWith(".txt")) continue
            net.mailboxDelivered(name.removeSuffix(".txt"))
            delete("$dir/$name", o.optString("sha"))
            etags.remove(dir)
        }
    }
}
