package fr.acinq.lightning.wire

import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.utils.sat
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
sealed class TxAddInputTlv : Tlv

@Serializable
sealed class TxAddOutputTlv : Tlv

@Serializable
sealed class TxRemoveInputTlv : Tlv

@Serializable
sealed class TxRemoveOutputTlv : Tlv

@Serializable
sealed class TxCompleteTlv : Tlv

@Serializable
sealed class TxSignaturesTlv : Tlv

@Serializable
sealed class TxInitRbfTlv : Tlv {
    /** Amount that the peer will contribute to the transaction's shared output. */
    @Serializable
    data class SharedOutputContributionTlv(@Contextual val amount: Satoshi) : TxInitRbfTlv() {
        override val tag: Long get() = SharedOutputContributionTlv.tag

        override fun write(out: Output) = LightningCodecs.writeTU64(amount.toLong(), out)

        companion object : TlvValueReader<SharedOutputContributionTlv> {
            const val tag: Long = 0

            override fun read(input: Input): SharedOutputContributionTlv = SharedOutputContributionTlv(LightningCodecs.tu64(input).sat)
        }
    }
}

@Serializable
sealed class TxAckRbfTlv : Tlv {
    /** Amount that the peer will contribute to the transaction's shared output. */
    @Serializable
    data class SharedOutputContributionTlv(@Contextual val amount: Satoshi) : TxAckRbfTlv() {
        override val tag: Long get() = SharedOutputContributionTlv.tag

        override fun write(out: Output) = LightningCodecs.writeTU64(amount.toLong(), out)

        companion object : TlvValueReader<SharedOutputContributionTlv> {
            const val tag: Long = 0

            override fun read(input: Input): SharedOutputContributionTlv = SharedOutputContributionTlv(LightningCodecs.tu64(input).sat)
        }
    }
}

@Serializable
sealed class TxAbortTlv : Tlv