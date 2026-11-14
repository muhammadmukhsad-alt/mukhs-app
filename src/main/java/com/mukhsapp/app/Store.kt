package com.mukhsapp.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// state of a message I sent: 0 = waiting (clock), 1 = sent (one tick),
// 2 = delivered to their phone (two grey ticks), 3 = read (two blue ticks)
data class Msg(
    val id: Long,
    val uid: String,
    val mine: Boolean,
    val body: String,
    val ts: Long,
    val state: Int,
    val react: String = "",
    val star: Boolean = false,
    val quote: String = "",
    val qmine: Boolean = false,
    val kind: String = "t",
    val path: String = "",
    val dur: Int = 0
)

// a status (24 hours). owner = "" means it is mine.
data class Status(
    val id: Long,
    val owner: String,
    val uid: String,
    val kind: String,
    val text: String,
    val bg: Int,
    val img: String,
    val aud: String,
    val ts: Long,
    val viewed: Boolean
)

data class StatusOwner(val owner: String, val total: Int, val unviewed: Int, val latest: Long)

data class Convo(val peer: String, val last: Msg, val unread: Int)

private const val COLS = "id, uid, mine, body, ts, state, react, star, quote, qmine, kind, path, dur"

class Store(ctx: Context) : SQLiteOpenHelper(ctx, "mukhs.db", null, 8) {

    private fun create(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE msgs(id INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT, " +
                "mine INTEGER, body TEXT, ts INTEGER, sent INTEGER, uid TEXT, " +
                "state INTEGER DEFAULT 0, unread INTEGER DEFAULT 0, racked INTEGER DEFAULT 1, del INTEGER DEFAULT 0, " +
                "react TEXT DEFAULT '', star INTEGER DEFAULT 0, quote TEXT DEFAULT '', qmine INTEGER DEFAULT 0, " +
                "kind TEXT DEFAULT 't', path TEXT DEFAULT '', dur INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE TABLE peers(hash TEXT PRIMARY KEY, name TEXT, last_seen INTEGER DEFAULT 0, about TEXT DEFAULT '', pub TEXT DEFAULT '')")
    }

    // envelopes I carry for other people (store-and-forward). done=1 means it reached its owner
    // (kept without its content so I can tell other phones to forget it too).
    private fun createRelay(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS relay(uid TEXT PRIMARY KEY, dst TEXT, ts INTEGER, exp INTEGER, " +
                "hops INTEGER DEFAULT 0, blob TEXT DEFAULT '', done INTEGER DEFAULT 0)"
        )
    }

    private fun createStatus(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS statuses(id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT, uid TEXT UNIQUE, " +
                "kind TEXT, text TEXT, bg INTEGER, img TEXT, aud TEXT, ts INTEGER, viewed INTEGER DEFAULT 0, vsent INTEGER DEFAULT 1)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS status_views(uid TEXT, viewer TEXT, ts INTEGER, PRIMARY KEY(uid, viewer))")
    }

    override fun onCreate(db: SQLiteDatabase) {
        create(db)
        createStatus(db)
        createRelay(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS msgs")
            db.execSQL("DROP TABLE IF EXISTS peers")
            create(db)
            createStatus(db)
            createRelay(db)
        } else {
            if (oldVersion < 3) db.execSQL("ALTER TABLE peers ADD COLUMN about TEXT DEFAULT ''")
            if (oldVersion < 4) db.execSQL("ALTER TABLE msgs ADD COLUMN del INTEGER DEFAULT 0")
            if (oldVersion < 5) {
                db.execSQL("ALTER TABLE msgs ADD COLUMN react TEXT DEFAULT ''")
                db.execSQL("ALTER TABLE msgs ADD COLUMN star INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE msgs ADD COLUMN quote TEXT DEFAULT ''")
                db.execSQL("ALTER TABLE msgs ADD COLUMN qmine INTEGER DEFAULT 0")
            }
            if (oldVersion < 7) createStatus(db)
            if (oldVersion < 8) {
                db.execSQL("ALTER TABLE peers ADD COLUMN pub TEXT DEFAULT ''")
                createRelay(db)
            }
            if (oldVersion < 6) {
                db.execSQL("ALTER TABLE msgs ADD COLUMN kind TEXT DEFAULT 't'")
                db.execSQL("ALTER TABLE msgs ADD COLUMN path TEXT DEFAULT ''")
                db.execSQL("ALTER TABLE msgs ADD COLUMN dur INTEGER DEFAULT 0")
            }
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // messages deleted earlier (after the UNDO time) are removed for good
        db.execSQL("DELETE FROM msgs WHERE del=1")
        db.execSQL("DELETE FROM statuses WHERE ts < " + (System.currentTimeMillis() - 86400000L))
        db.execSQL("DELETE FROM relay WHERE exp < " + System.currentTimeMillis())
    }

    private fun readMsg(c: Cursor): Msg =
        Msg(
            c.getLong(0),
            c.getString(1) ?: "",
            c.getInt(2) == 1,
            c.getString(3) ?: "",
            c.getLong(4),
            c.getInt(5),
            c.getString(6) ?: "",
            c.getInt(7) == 1,
            c.getString(8) ?: "",
            c.getInt(9) == 1,
            c.getString(10) ?: "t",
            c.getString(11) ?: "",
            c.getInt(12)
        )

    // ---------- messages ----------

    fun insertOut(peer: String, uid: String, body: String, ts: Long, state: Int, quote: String = "", qmine: Boolean = false, kind: String = "t", path: String = "", dur: Int = 0): Long {
        val cv = ContentValues()
        cv.put("peer", peer)
        cv.put("mine", 1)
        cv.put("body", body)
        cv.put("ts", ts)
        cv.put("sent", if (state > 0) 1 else 0)
        cv.put("uid", uid)
        cv.put("state", state)
        cv.put("unread", 0)
        cv.put("racked", 1)
        cv.put("quote", quote)
        cv.put("qmine", if (qmine) 1 else 0)
        cv.put("kind", kind)
        cv.put("path", path)
        cv.put("dur", dur)
        return writableDatabase.insert("msgs", null, cv)
    }

    fun insertIn(peer: String, uid: String, body: String, ts: Long, unread: Boolean, quote: String = "", qmine: Boolean = false, kind: String = "t", path: String = "", dur: Int = 0): Long {
        val cv = ContentValues()
        cv.put("peer", peer)
        cv.put("mine", 0)
        cv.put("body", body)
        cv.put("ts", ts)
        cv.put("sent", 1)
        cv.put("uid", uid)
        cv.put("state", 0)
        cv.put("unread", if (unread) 1 else 0)
        cv.put("racked", 1)
        cv.put("quote", quote)
        cv.put("qmine", if (qmine) 1 else 0)
        cv.put("kind", kind)
        cv.put("path", path)
        cv.put("dur", dur)
        return writableDatabase.insert("msgs", null, cv)
    }

    fun hasUid(uid: String): Boolean {
        val c = readableDatabase.rawQuery("SELECT 1 FROM msgs WHERE uid=? LIMIT 1", arrayOf(uid))
        val r = c.moveToFirst()
        c.close()
        return r
    }

    // only ever moves a message forward (0 -> 1 -> 2 -> 3)
    fun setState(uid: String, state: Int) {
        if (uid.isEmpty()) return
        writableDatabase.execSQL(
            "UPDATE msgs SET state=?, sent=1 WHERE uid=? AND mine=1 AND state<?",
            arrayOf<Any?>(state, uid, state)
        )
    }

    fun messages(peer: String): List<Msg> {
        val out = ArrayList<Msg>()
        val c = readableDatabase.rawQuery(
            "SELECT $COLS FROM msgs WHERE peer=? AND del=0 ORDER BY id", arrayOf(peer)
        )
        while (c.moveToNext()) out.add(readMsg(c))
        c.close()
        return out
    }

    fun pendingOut(peer: String): List<Msg> {
        val out = ArrayList<Msg>()
        val c = readableDatabase.rawQuery(
            "SELECT $COLS FROM msgs WHERE peer=? AND del=0 AND mine=1 AND state=0 AND uid IS NOT NULL ORDER BY id",
            arrayOf(peer)
        )
        while (c.moveToNext()) out.add(readMsg(c))
        c.close()
        return out
    }

    // marks received messages as read; returns their ids so the sender can be told
    fun markRead(peer: String): List<String> {
        val uids = ArrayList<String>()
        val c = readableDatabase.rawQuery(
            "SELECT uid FROM msgs WHERE peer=? AND mine=0 AND unread=1 AND uid IS NOT NULL",
            arrayOf(peer)
        )
        while (c.moveToNext()) uids.add(c.getString(0) ?: "")
        c.close()
        writableDatabase.execSQL(
            "UPDATE msgs SET unread=0, racked=0 WHERE peer=? AND mine=0 AND unread=1",
            arrayOf<Any?>(peer)
        )
        return uids.filter { it.isNotEmpty() }
    }

    // messages I have read but whose "read" receipt has not reached the sender yet
    fun unackedReads(peer: String): List<String> {
        val uids = ArrayList<String>()
        val c = readableDatabase.rawQuery(
            "SELECT uid FROM msgs WHERE peer=? AND mine=0 AND unread=0 AND racked=0 AND uid IS NOT NULL",
            arrayOf(peer)
        )
        while (c.moveToNext()) uids.add(c.getString(0) ?: "")
        c.close()
        return uids.filter { it.isNotEmpty() }
    }

    fun setRacked(uids: List<String>) {
        for (u in uids) {
            writableDatabase.execSQL("UPDATE msgs SET racked=1 WHERE uid=?", arrayOf<Any?>(u))
        }
    }

    fun unreadCount(peer: String): Int {
        val c = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM msgs WHERE peer=? AND del=0 AND mine=0 AND unread=1", arrayOf(peer)
        )
        var n = 0
        if (c.moveToFirst()) n = c.getInt(0)
        c.close()
        return n
    }

    fun totalUnread(): Int {
        val c = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM msgs WHERE del=0 AND mine=0 AND unread=1", null
        )
        var n = 0
        if (c.moveToFirst()) n = c.getInt(0)
        c.close()
        return n
    }

    fun convos(): List<Convo> {
        val ids = ArrayList<Pair<String, Long>>()
        val c = readableDatabase.rawQuery(
            "SELECT peer, MAX(id) FROM msgs WHERE del=0 GROUP BY peer ORDER BY MAX(id) DESC", null
        )
        while (c.moveToNext()) ids.add(Pair(c.getString(0) ?: "", c.getLong(1)))
        c.close()
        val out = ArrayList<Convo>()
        for (p in ids) {
            val c2 = readableDatabase.rawQuery(
                "SELECT $COLS FROM msgs WHERE id=?", arrayOf(p.second.toString())
            )
            if (c2.moveToFirst()) out.add(Convo(p.first, readMsg(c2), unreadCount(p.first)))
            c2.close()
        }
        return out
    }

    // ---------- people ----------

    fun savePeer(hash: String, name: String) {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO peers(hash, name, last_seen) VALUES(?, ?, 0)",
            arrayOf<Any?>(hash, name)
        )
        writableDatabase.execSQL(
            "UPDATE peers SET name=? WHERE hash=?",
            arrayOf<Any?>(name, hash)
        )
    }

    fun peerName(hash: String): String {
        val c = readableDatabase.rawQuery("SELECT name FROM peers WHERE hash=?", arrayOf(hash))
        var name = "Friend"
        if (c.moveToFirst()) name = c.getString(0) ?: "Friend"
        c.close()
        return name
    }

    fun touchSeen(hash: String) {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO peers(hash, name, last_seen) VALUES(?, 'Friend', ?)",
            arrayOf<Any?>(hash, now)
        )
        writableDatabase.execSQL(
            "UPDATE peers SET last_seen=? WHERE hash=?",
            arrayOf<Any?>(now, hash)
        )
    }

    fun lastSeen(hash: String): Long {
        val c = readableDatabase.rawQuery("SELECT last_seen FROM peers WHERE hash=?", arrayOf(hash))
        var t = 0L
        if (c.moveToFirst()) t = c.getLong(0)
        c.close()
        return t
    }

    fun setAbout(hash: String, about: String) {
        writableDatabase.execSQL(
            "UPDATE peers SET about=? WHERE hash=?",
            arrayOf<Any?>(about, hash)
        )
    }

    fun about(hash: String): String {
        val c = readableDatabase.rawQuery("SELECT about FROM peers WHERE hash=?", arrayOf(hash))
        var a = ""
        if (c.moveToFirst()) a = c.getString(0) ?: ""
        c.close()
        return a
    }

    // ---------- public keys and relay (store-and-forward) ----------

    fun setPub(hash: String, pub: String) {
        writableDatabase.execSQL("UPDATE peers SET pub=? WHERE hash=?", arrayOf<Any?>(pub, hash))
    }

    fun pub(hash: String): String {
        val c = readableDatabase.rawQuery("SELECT pub FROM peers WHERE hash=?", arrayOf(hash))
        var p = ""
        if (c.moveToFirst()) p = c.getString(0) ?: ""
        c.close()
        return p
    }

    fun hasRelay(uid: String): Boolean {
        val c = readableDatabase.rawQuery("SELECT 1 FROM relay WHERE uid=? LIMIT 1", arrayOf(uid))
        val r = c.moveToFirst()
        c.close()
        return r
    }

    fun relayCount(): Int {
        val c = readableDatabase.rawQuery("SELECT COUNT(*) FROM relay WHERE done=0", null)
        var n = 0
        if (c.moveToFirst()) n = c.getInt(0)
        c.close()
        return n
    }

    fun putRelay(uid: String, dst: String, ts: Long, hops: Int, blob: String) {
        val cv = ContentValues()
        cv.put("uid", uid)
        cv.put("dst", dst)
        cv.put("ts", ts)
        cv.put("exp", ts + Relay.TTL_MS)
        cv.put("hops", hops)
        cv.put("blob", blob)
        cv.put("done", 0)
        writableDatabase.insertWithOnConflict("relay", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        // keep at most MAX_CARRY envelopes: drop the oldest first
        writableDatabase.execSQL(
            "DELETE FROM relay WHERE done=0 AND uid NOT IN (SELECT uid FROM relay WHERE done=0 ORDER BY ts DESC LIMIT " +
                Relay.MAX_CARRY + ")"
        )
    }

    // everything I carry, so a phone I meet can say which ones it is missing
    fun relayIds(): List<String> {
        val out = ArrayList<String>()
        val c = readableDatabase.rawQuery(
            "SELECT uid FROM relay WHERE done=0 AND exp>? ORDER BY ts DESC LIMIT " + Relay.MAX_HELLO,
            arrayOf(System.currentTimeMillis().toString())
        )
        while (c.moveToNext()) out.add(c.getString(0) ?: "")
        c.close()
        return out
    }

    fun relayDoneIds(): List<String> {
        val out = ArrayList<String>()
        val c = readableDatabase.rawQuery(
            "SELECT uid FROM relay WHERE done=1 AND exp>? ORDER BY ts DESC LIMIT " + Relay.MAX_HELLO,
            arrayOf(System.currentTimeMillis().toString())
        )
        while (c.moveToNext()) out.add(c.getString(0) ?: "")
        c.close()
        return out
    }

    // an envelope I should hand to a phone that does not have it: (uid, dst, ts, hops, blob)
    class Env(val uid: String, val dst: String, val ts: Long, val hops: Int, val blob: String)

    fun relayGet(uid: String): Env? {
        val c = readableDatabase.rawQuery(
            "SELECT uid, dst, ts, hops, blob FROM relay WHERE uid=? AND done=0", arrayOf(uid)
        )
        var e: Env? = null
        if (c.moveToFirst()) e = Env(c.getString(0) ?: "", c.getString(1) ?: "", c.getLong(2), c.getInt(3), c.getString(4) ?: "")
        c.close()
        return e
    }

    // it reached its owner: forget the content, remember the id (so others forget it too)
    fun relayDone(uid: String, ts: Long) {
        writableDatabase.execSQL(
            "UPDATE relay SET dst='', blob='', done=1 WHERE uid=?", arrayOf<Any?>(uid)
        )
        val cv = ContentValues()
        cv.put("uid", uid)
        cv.put("dst", "")
        cv.put("ts", ts)
        cv.put("exp", ts + Relay.TTL_MS)
        cv.put("blob", "")
        cv.put("done", 1)
        writableDatabase.insertWithOnConflict("relay", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    // ---------- statuses ----------

    private fun cutoff(): Long = System.currentTimeMillis() - 86400000L

    private fun readStatus(c: Cursor): Status =
        Status(
            c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "t",
            c.getString(4) ?: "", c.getInt(5), c.getString(6) ?: "", c.getString(7) ?: "",
            c.getLong(8), c.getInt(9) == 1
        )

    private val SCOLS = "id, owner, uid, kind, text, bg, img, aud, ts, viewed"

    fun addStatus(s: Status): Long {
        val cv = ContentValues()
        cv.put("owner", s.owner)
        cv.put("uid", s.uid)
        cv.put("kind", s.kind)
        cv.put("text", s.text)
        cv.put("bg", s.bg)
        cv.put("img", s.img)
        cv.put("aud", s.aud)
        cv.put("ts", s.ts)
        cv.put("viewed", if (s.viewed) 1 else 0)
        cv.put("vsent", 1)
        return writableDatabase.insertWithOnConflict("statuses", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun statuses(owner: String): List<Status> {
        val out = ArrayList<Status>()
        val c = readableDatabase.rawQuery(
            "SELECT $SCOLS FROM statuses WHERE owner=? AND ts>? ORDER BY ts", arrayOf(owner, cutoff().toString())
        )
        while (c.moveToNext()) out.add(readStatus(c))
        c.close()
        return out
    }

    // people (not me) who have a status now
    fun statusOwners(): List<StatusOwner> {
        val out = ArrayList<StatusOwner>()
        val c = readableDatabase.rawQuery(
            "SELECT owner, COUNT(*), SUM(CASE WHEN viewed=0 THEN 1 ELSE 0 END), MAX(ts) FROM statuses " +
                "WHERE owner<>'' AND ts>? GROUP BY owner ORDER BY MAX(ts) DESC", arrayOf(cutoff().toString())
        )
        while (c.moveToNext()) out.add(StatusOwner(c.getString(0) ?: "", c.getInt(1), c.getInt(2), c.getLong(3)))
        c.close()
        return out
    }

    fun statusInfo(owner: String): StatusOwner? {
        for (o in statusOwners()) if (o.owner == owner) return o
        return null
    }

    fun totalUnviewedStatuses(): Int {
        var n = 0
        for (o in statusOwners()) n += o.unviewed
        return n
    }

    fun hasStatus(uid: String): Boolean {
        val c = readableDatabase.rawQuery("SELECT 1 FROM statuses WHERE uid=? LIMIT 1", arrayOf(uid))
        val r = c.moveToFirst()
        c.close()
        return r
    }

    fun deleteStatus(uid: String) {
        writableDatabase.execSQL("DELETE FROM statuses WHERE uid=?", arrayOf<Any?>(uid))
        writableDatabase.execSQL("DELETE FROM status_views WHERE uid=?", arrayOf<Any?>(uid))
    }

    // the owner's list is the truth: remove what they no longer have
    fun keepOnlyStatuses(owner: String, ids: Collection<String>) {
        val c = readableDatabase.rawQuery("SELECT uid FROM statuses WHERE owner=?", arrayOf(owner))
        val gone = ArrayList<String>()
        while (c.moveToNext()) {
            val u = c.getString(0) ?: ""
            if (!ids.contains(u)) gone.add(u)
        }
        c.close()
        for (u in gone) deleteStatus(u)
    }

    // returns true if this is the first time I see it
    fun markStatusViewed(uid: String): Boolean {
        val c = readableDatabase.rawQuery("SELECT viewed FROM statuses WHERE uid=?", arrayOf(uid))
        var first = false
        if (c.moveToFirst()) first = c.getInt(0) == 0
        c.close()
        if (first) writableDatabase.execSQL("UPDATE statuses SET viewed=1, vsent=0 WHERE uid=?", arrayOf<Any?>(uid))
        return first
    }

    fun setViewSent(uid: String) {
        writableDatabase.execSQL("UPDATE statuses SET vsent=1 WHERE uid=?", arrayOf<Any?>(uid))
    }

    fun unsentViews(owner: String): List<String> {
        val out = ArrayList<String>()
        val c = readableDatabase.rawQuery("SELECT uid FROM statuses WHERE owner=? AND vsent=0", arrayOf(owner))
        while (c.moveToNext()) out.add(c.getString(0) ?: "")
        c.close()
        return out
    }

    fun addView(uid: String, viewer: String, ts: Long) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO status_views(uid, viewer, ts) VALUES(?,?,?)",
            arrayOf<Any?>(uid, viewer, ts)
        )
    }

    fun views(uid: String): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        val c = readableDatabase.rawQuery("SELECT viewer, ts FROM status_views WHERE uid=? ORDER BY ts DESC", arrayOf(uid))
        while (c.moveToNext()) out.add(Pair(c.getString(0) ?: "", c.getLong(1)))
        c.close()
        return out
    }

    fun setReact(uid: String, e: String) {
        if (uid.isEmpty()) return
        writableDatabase.execSQL("UPDATE msgs SET react=? WHERE uid=?", arrayOf<Any?>(e, uid))
    }

    fun setStar(ids: List<Long>, star: Boolean) {
        for (i in ids) {
            writableDatabase.execSQL("UPDATE msgs SET star=? WHERE id=?", arrayOf<Any?>(if (star) 1 else 0, i))
        }
    }

    fun setDeleted(ids: List<Long>, deleted: Boolean) {
        for (i in ids) {
            writableDatabase.execSQL("UPDATE msgs SET del=? WHERE id=?", arrayOf<Any?>(if (deleted) 1 else 0, i))
        }
    }

    fun clearChat(peer: String) {
        writableDatabase.execSQL("DELETE FROM msgs WHERE peer=?", arrayOf<Any?>(peer))
    }
}
