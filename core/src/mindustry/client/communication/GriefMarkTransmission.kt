package mindustry.client.communication

import mindustry.client.*
import mindustry.client.crypto.*
import mindustry.client.utils.*
import java.security.cert.*
import java.time.*
import kotlin.random.*

/**
 * Grief report shared between client users of one team: who, where and the evidence (blocks broken / built).
 * [reason]: 0 = the sender's auto detector, 1 = reported by hand.
 * Signed with the sender's key when they have one, so a trusted reporter is shown as verified
 * (the relay already tells us which player sent it, the signature adds who that is across servers).
 */
class GriefMarkTransmission : Transmission {

    val reason: Int
    val suspectId: Int
    val x: Float
    val y: Float
    val breaks: Int
    val builds: Int
    val timestamp: Instant
    var certSN: ByteArray = byteArrayOf()
    var signature: ByteArray = byteArrayOf()

    override var id: Long
    override val secureOnly: Boolean = false

    constructor(reason: Int, suspectId: Int, x: Float, y: Float, breaks: Int, builds: Int) {
        this.reason = reason
        this.suspectId = suspectId
        this.x = x
        this.y = y
        this.breaks = breaks
        this.builds = builds
        timestamp = Main.ntp.instant()
        id = Random.nextLong()
        sign()
    }

    constructor(input: ByteArray, id: Long, @Suppress("UNUSED_PARAMETER") senderID: Int) {
        val buf = input.buffer()
        reason = buf.int
        suspectId = buf.int
        x = Float.fromBits(buf.int)
        y = Float.fromBits(buf.int)
        breaks = buf.int
        builds = buf.int
        timestamp = buf.long.toInstant()
        certSN = buf.bytes(buf.int)
        signature = buf.bytes(buf.int)
        this.id = id
    }

    override fun serialize() = reason.toBytes() + suspectId.toBytes() + x.toBits().toBytes() + y.toBits().toBytes() +
        breaks.toBytes() + builds.toBytes() + timestamp.epochSecond.toBytes() +
        certSN.size.toBytes() + certSN + signature.size.toBytes() + signature

    private fun toSignable() = reason.toBytes() + suspectId.toBytes() + x.toBits().toBytes() + y.toBits().toBytes() +
        breaks.toBytes() + builds.toBytes() + id.toBytes() + timestamp.epochSecond.toBytes()

    private fun sign() {
        val cert = Main.keyStorage.cert() ?: return
        val sig = Main.signatures.sign(toSignable()) ?: return
        certSN = cert.serialNumber.toByteArray()
        signature = sig
    }

    /** The trusted certificate that signed this report, or null when unsigned, unknown, forged or stale. */
    fun verifiedBy(): X509Certificate? {
        if (certSN.isEmpty() || signature.size != Signatures.SIGNATURE_LENGTH) return null
        // a bit looser than chat signatures: the slow processor transport can take a while
        if (timestamp.ageNTP() > 60) return null
        val res = Main.signatures.verify(toSignable(), signature, certSN)
        return if (res.first == Signatures.VerifyResult.VALID) res.second else null
    }
}
