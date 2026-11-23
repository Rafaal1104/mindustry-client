package mindustry.client.communication

import mindustry.client.utils.*
import java.nio.*
import kotlin.random.*

/**
 * Map ping shared between client users of one team: "attack here", "need help" and the like.
 * [kind] is the index in MapPings.Kind; unknown kinds from newer clients are dropped on receive.
 */
class PingTransmission(val kind: Int, val x: Float, val y: Float) : Transmission {

    override var id = Random.nextLong()
    override val secureOnly: Boolean = false

    private constructor(buf: ByteBuffer) : this(buf.int, Float.fromBits(buf.int), Float.fromBits(buf.int))

    constructor(input: ByteArray, id: Long, @Suppress("UNUSED_PARAMETER") senderID: Int) : this(input.buffer()) {
        this.id = id
    }

    override fun serialize() = kind.toBytes() + x.toBits().toBytes() + y.toBits().toBytes()
}
