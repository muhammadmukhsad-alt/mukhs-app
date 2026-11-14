package com.mukhsapp.app

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Store-and-forward ("carry it with me") messaging, no internet.
//
// A message to someone who is not near me is sealed (end-to-end encrypted) for them and kept
// in my phone as an "envelope". Whenever my phone meets any other MukhsApp phone, we swap the
// envelopes we are each carrying. So the envelope travels with people until it meets its owner.
// Carriers cannot read it: only the receiver's private key opens it.
//
// Keys: one EC P-256 key pair per phone, made on first start, kept in app-private storage.
// Public keys are exchanged the first time two phones are connected (in the name card).
object Relay {

    const val MAX_HOPS = 8
    const val TTL_MS = 7L * 24 * 3600 * 1000     // an envelope is dropped after 7 days
    const val MAX_CARRY = 300                     // how many envelopes one phone carries at most
    const val MAX_HELLO = 300                     // how many ids we list when two phones meet
    const val MAX_BLOB = 22000                    // one envelope must fit in one 32 KB payload

    private const val PREF = "mukhs_keys"
    private val rnd = SecureRandom()

    // ---------- my key pair ----------

    private fun pair(ctx: Context): KeyPair {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val pub = sp.getString("pub", null)
        val prv = sp.getString("prv", null)
        val kf = KeyFactory.getInstance("EC")
        if (pub != null && prv != null) {
            try {
                return KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
                    kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(prv, Base64.NO_WRAP)))
                )
            } catch (e: Exception) {
                // fall through and make a new pair
            }
        }
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        val kp = g.generateKeyPair()
        sp.edit()
            .putString("pub", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .putString("prv", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .apply()
        return kp
    }

    fun myPub(ctx: Context): String =
        Base64.encodeToString(pair(ctx).public.encoded, Base64.NO_WRAP)

    private fun pubOf(b64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(b64, Base64.NO_WRAP)))

    private fun aesKey(secret: ByteArray): SecretKeySpec {
        val k = MessageDigest.getInstance("SHA-256").digest(secret)
        return SecretKeySpec(k, "AES")
    }

    // ---------- seal and open ----------

    // Returns the sealed blob (JSON text) that only the owner of dstPub can open, or null.
    // Inside: who it is from, a signature by the sender, and the message itself.
    fun seal(ctx: Context, dstPub: String, dst: String, src: String, uid: String, ts: Long, inner: JSONObject): String? {
        try {
            val me = pair(ctx)
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(me.private)
            sig.update((uid + "|" + dst + "|" + ts + "|" + inner.toString()).toByteArray(Charsets.UTF_8))
            val plain = JSONObject()
                .put("src", src).put("ts", ts).put("in", inner)
                .put("spub", myPub(ctx))
                .put("sig", Base64.encodeToString(sig.sign(), Base64.NO_WRAP))
                .toString().toByteArray(Charsets.UTF_8)

            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec("secp256r1"))
            val eph = g.generateKeyPair()
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(eph.private)
            ka.doPhase(pubOf(dstPub), true)
            val key = aesKey(ka.generateSecret())

            val iv = ByteArray(12)
            rnd.nextBytes(iv)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            c.updateAAD((uid + "|" + dst).toByteArray(Charsets.UTF_8))
            val ct = c.doFinal(plain)

            val blob = JSONObject()
                .put("e", Base64.encodeToString(eph.public.encoded, Base64.NO_WRAP))
                .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .put("c", Base64.encodeToString(ct, Base64.NO_WRAP))
                .toString()
            return if (blob.length <= MAX_BLOB) blob else null
        } catch (e: Exception) {
            return null
        }
    }

    class Opened(val src: String, val ts: Long, val inner: JSONObject, val senderPub: String, val verified: Boolean)

    // Opens a blob addressed to me. Returns null if it is not for me or was changed on the way.
    fun open(ctx: Context, uid: String, dst: String, blob: String): Opened? {
        try {
            val j = JSONObject(blob)
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(pair(ctx).private)
            ka.doPhase(pubOf(j.getString("e")), true)
            val key = aesKey(ka.generateSecret())
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(128, Base64.decode(j.getString("iv"), Base64.NO_WRAP))
            )
            c.updateAAD((uid + "|" + dst).toByteArray(Charsets.UTF_8))
            val plain = JSONObject(String(c.doFinal(Base64.decode(j.getString("c"), Base64.NO_WRAP)), Charsets.UTF_8))

            val inner = plain.getJSONObject("in")
            val ts = plain.getLong("ts")
            val spub = plain.getString("spub")
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pubOf(spub))
            sig.update((uid + "|" + dst + "|" + ts + "|" + inner.toString()).toByteArray(Charsets.UTF_8))
            val ok = sig.verify(Base64.decode(plain.getString("sig"), Base64.NO_WRAP))
            if (!ok) return null
            return Opened(plain.getString("src"), ts, inner, spub, true)
        } catch (e: Exception) {
            return null
        }
    }
}
