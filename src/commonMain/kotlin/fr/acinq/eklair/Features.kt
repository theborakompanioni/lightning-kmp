package fr.acinq.eklair

import fr.acinq.bitcoin.ByteVector
import fr.acinq.eklair.utils.BitField
import fr.acinq.eklair.utils.leftPaddedCopyOf
import fr.acinq.eklair.utils.or
import kotlinx.serialization.Serializable


enum class FeatureSupport {
    Mandatory {
        override fun toString() = "mandatory"
    },
    Optional {
        override fun toString() = "optional"
    }
}

@Serializable
sealed class Feature {

    abstract val rfcName: String
    abstract val mandatory: Int
    val optional: Int get() = mandatory + 1

    fun supportBit(support: FeatureSupport): Int = when (support) {
        FeatureSupport.Mandatory -> mandatory
        FeatureSupport.Optional -> optional
    }

    override fun toString() = rfcName

    @Serializable
    object OptionDataLossProtect : Feature() {
        override val rfcName get() = "option_data_loss_protect"
        override val mandatory get() = 0
    }

    @Serializable
    object InitialRoutingSync : Feature() {
        override val rfcName get() = "initial_routing_sync"

        // reserved but not used as per lightningnetwork/lightning-rfc/pull/178
        override val mandatory get() = 2
    }

    @Serializable
    object ChannelRangeQueries : Feature() {
        override val rfcName get() = "gossip_queries"
        override val mandatory get() = 6
    }

    @Serializable
    object VariableLengthOnion : Feature() {
        override val rfcName get() = "var_onion_optin"
        override val mandatory get() = 8
    }

    @Serializable
    object ChannelRangeQueriesExtended : Feature() {
        override val rfcName get() = "gossip_queries_ex"
        override val mandatory get() = 10
    }

    @Serializable
    object StaticRemoteKey : Feature() {
        override val rfcName get() = "option_static_remotekey"
        override val mandatory get() = 12
    }

    @Serializable
    object PaymentSecret : Feature() {
        override val rfcName get() = "payment_secret"
        override val mandatory get() = 14
    }

    @Serializable
    object BasicMultiPartPayment : Feature() {
        override val rfcName get() = "basic_mpp"
        override val mandatory get()  = 16
    }

    @Serializable
    object Wumbo : Feature() {
        override val rfcName get() = "option_support_large_channel"
        override val mandatory get() = 18
    }

    // TODO: @t-bast: update feature bits once spec-ed (currently reserved here: https://github.com/lightningnetwork/lightning-rfc/issues/605)
    // We're not advertising these bits yet in our announcements, clients have to assume support.
    // This is why we haven't added them yet to `areSupported`.
    @Serializable
    object TrampolinePayment : Feature() {
        override val rfcName get() = "trampoline_payment"
        override val mandatory get() = 50
    }
}

@Serializable
data class ActivatedFeature(val feature: Feature, val support: FeatureSupport)

@Serializable
data class UnknownFeature(val bitIndex: Int)

@Serializable
data class Features(val activated: Set<ActivatedFeature>, val unknown: Set<UnknownFeature> = emptySet()) {

    fun hasFeature(feature: Feature, support: FeatureSupport? = null): Boolean =
        if (support != null) activated.contains(ActivatedFeature(feature, support))
        else hasFeature(feature, FeatureSupport.Optional) || hasFeature(feature, FeatureSupport.Mandatory)

    fun toByteArray(): ByteArray {
        val activatedFeatureBytes =
            activated.mapTo(HashSet()) { it.feature.supportBit(it.support) }.indicesToByteArray()
        val unknownFeatureBytes = unknown.mapTo(HashSet()) { it.bitIndex }.indicesToByteArray()
        val maxSize = activatedFeatureBytes.size.coerceAtLeast(unknownFeatureBytes.size)
        return activatedFeatureBytes.leftPaddedCopyOf(maxSize) or unknownFeatureBytes.leftPaddedCopyOf(maxSize)
    }

    private fun Set<Int>.indicesToByteArray(): ByteArray {
        if (isEmpty()) return ByteArray(0)
        // When converting from BitVector to ByteVector, scodec pads right instead of left, so we make sure we pad to bytes *before* setting feature bits.
        val buf = BitField.forAtMost(maxOrNull()!! + 1)
        forEach { buf.setRight(it) }
        return buf.bytes
    }

    /**
     * Eclair-mobile thinks feature bit 15 (payment_secret) is gossip_queries_ex which creates issues, so we mask
     * off basic_mpp and payment_secret. As long as they're provided in the invoice it's not an issue.
     * We use a long enough mask to account for future features.
     * TODO: remove that once eclair-mobile is patched.
     */
    fun maskFeaturesForEclairMobile(): Features =
        Features(
            activated = activated.filterTo(HashSet()) {
                when (it.feature) {
                    is Feature.PaymentSecret -> false
                    is Feature.BasicMultiPartPayment -> false
                    else -> true
                }
            },
            unknown = unknown
        )

    companion object {
        val empty = Features(emptySet())

        val knownFeatures: Set<Feature> = setOf(
            Feature.OptionDataLossProtect,
            Feature.InitialRoutingSync,
            Feature.ChannelRangeQueries,
            Feature.VariableLengthOnion,
            Feature.ChannelRangeQueriesExtended,
            Feature.StaticRemoteKey,
            Feature.PaymentSecret,
            Feature.BasicMultiPartPayment,
            Feature.Wumbo,
            Feature.TrampolinePayment
        )

        private val supportedMandatoryFeatures: Set<Feature> = setOf(
            Feature.OptionDataLossProtect,
            Feature.ChannelRangeQueries,
            Feature.VariableLengthOnion,
            Feature.ChannelRangeQueriesExtended,
            Feature.PaymentSecret,
            Feature.BasicMultiPartPayment,
            Feature.Wumbo
        )

        operator fun invoke(bytes: ByteVector): Features = invoke(bytes.toByteArray())

        operator fun invoke(bytes: ByteArray): Features = invoke(BitField.from(bytes))

        operator fun invoke(bits: BitField): Features {
            val all = bits.asRightSequence().withIndex()
                .filter { it.value }
                .map { (idx, _) ->
                    knownFeatures.find { it.optional == idx }?.let { ActivatedFeature(it, FeatureSupport.Optional) }
                        ?: knownFeatures.find { it.mandatory == idx }
                            ?.let { ActivatedFeature(it, FeatureSupport.Mandatory) }
                        ?: UnknownFeature(idx)
                }
                .toList()

            return Features(
                activated = all.filterIsInstance<ActivatedFeature>().toSet(),
                unknown = all.filterIsInstance<UnknownFeature>().toSet()
            )
        }

//        /** expects to have a top level config block named "features" */
//        fun fromConfiguration(config: Config): Features = Features(
//        knownFeatures.flatMap {
//            feature =>
//            getFeature(config, feature.rfcName) match {
//                case Some(support) => Some(ActivatedFeature(feature, support))
//                case _ => None
//            }
//        })
//
//        /** tries to extract the given feature name from the config, if successful returns its feature support */
//        private def getFeature(config: Config, name: String): Option[FeatureSupport] = {
//            if (!config.hasPath(s"features.$name")) None
//            else {
//                config.getString(s"features.$name") match {
//                    case support if support == Mandatory.toString => Some(Mandatory)
//                    case support if support == Optional.toString => Some(Optional)
//                    case wrongSupport => throw new IllegalArgumentException(s"Wrong support specified ($wrongSupport)")
//                }
//            }
//        }


        // Features may depend on other features, as specified in Bolt 9.
        private val featuresDependency: Map<Feature, List<Feature>> = mapOf(
            Feature.ChannelRangeQueriesExtended to listOf(Feature.ChannelRangeQueries),
            // This dependency requirement was added to the spec after the Phoenix release, which means Phoenix users have "invalid"
            // invoices in their payment history. We choose to treat such invoices as valid; this is a harmless spec violation.
            // PaymentSecret to listOf(VariableLengthOnion),
            Feature.BasicMultiPartPayment to listOf(Feature.PaymentSecret),
            Feature.TrampolinePayment to listOf(Feature.PaymentSecret)
        )

        class FeatureException(message: String) : IllegalArgumentException(message)

        fun validateFeatureGraph(features: Features): FeatureException? {
            featuresDependency.forEach { (feature, dependencies) ->
                if (features.hasFeature(feature)) {
                    val missing = dependencies.filter { it: Feature -> !features.hasFeature(it) }
                    if (missing.isNotEmpty()) {
                        return FeatureException("$feature is set but is missing a dependency (${missing.joinToString(" and ")})")
                    }
                }
            }
            return null
        }

        /**
         * A feature set is supported if all even bits are supported.
         * We just ignore unknown odd bits.
         */
        fun areSupported(features: Features): Boolean =
            !features.unknown.any { it.bitIndex % 2 == 0 } && features.activated.all {
                when (it.support) {
                    FeatureSupport.Optional -> true
                    FeatureSupport.Mandatory -> supportedMandatoryFeatures.contains(it.feature)
                }
            }

        /** returns true if both have at least optional support */
        fun canUseFeature(localFeatures: Features, remoteFeatures: Features, feature: Feature): Boolean =
            localFeatures.hasFeature(feature) && remoteFeatures.hasFeature(feature)

    }

}