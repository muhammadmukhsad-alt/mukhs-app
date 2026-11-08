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
    val state: Int
)

data class Convo(val peer: String, val last: Msg, val unread: Int)

private const val COLS = "id, uid, mine, body, ts, state"

class Store(ctx: Context) : SQLiteOpenHelper(ctx, "mukhs.db", null, 4) {

    private fun create(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE msgs(id INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT, " +
                "mine INTEGER, body TEXT, ts INTEGER, sent INTEGER, uid TEXT, " +
                "state INTEGER DEFAULT 0, unread INTEGER DEFAULT 0, racked INTEGER DEFAULT 1, del INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE TABLE peers(hash TEXT PRIMARY KEY, name TEXT, last_seen INTEGER DEFAULT 0, about TEXT DEFAULT '')")
    }

    override fun onCreate(db: SQLiteDatabase) {
        create(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS msgs")
            db.execSQL("DROP TABLE IF EXISTS peers")
            create(db)
        } else {
            if (oldVersion < 3) db.execSQL("ALTER TABLE peers ADD COLUMN about TEXT DEFAULT ''")
            if (oldVersion < 4) db.execSQL("ALTER TABLE msgs ADD COLUMN del INTEGER DEFAULT 0")
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // messages deleted earlier (after the UNDO time) are removed for good
        db.execSQL("DELETE FROM msgs WHERE del=1")
    }

    private fun readMsg(c: Cursor): Msg =
        Msg(
            c.getLong(0),
            c.getString(1) ?: "",
            c.getInt(2) == 1,
            c.getString(3) ?: "",
            c.getLong(4),
            c.getInt(5)
        )

    // ---------- messages ----------

    fun insertOut(peer: String, uid: String, body: String, ts: Long, state: Int): Long {
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
        return writableDatabase.insert("msgs", null, cv)
    }

    fun insertIn(peer: String, uid: String, body: String, ts: Long, unread: Boolean): Long {
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

    fun setDeleted(ids: List<Long>, deleted: Boolean) {
        for (i in ids) {
            writableDatabase.execSQL("UPDATE msgs SET del=? WHERE id=?", arrayOf<Any?>(if (deleted) 1 else 0, i))
        }
    }

    fun clearChat(peer: String) {
        writableDatabase.execSQL("DELETE FROM msgs WHERE peer=?", arrayOf<Any?>(peer))
    }
}
