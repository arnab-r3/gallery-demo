package com.r3.gallery.broker.corda.client.art.api

import com.r3.gallery.api.*
import com.r3.gallery.broker.corda.rpc.service.ConnectionManager
import com.r3.gallery.broker.corda.rpc.service.ConnectionService
import com.r3.gallery.states.ArtworkState
import com.r3.gallery.utils.AuctionCurrency
import com.r3.gallery.utils.getNotaryTransactionSignature
import com.r3.gallery.workflows.SignAndFinalizeTransferOfOwnership
import com.r3.gallery.workflows.artwork.FindArtworkFlow
import com.r3.gallery.workflows.artwork.FindOwnedArtworksFlow
import com.r3.gallery.workflows.artwork.IssueArtworkFlow
import net.corda.core.contracts.Amount
import net.corda.core.internal.toX500Name
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.WireTransaction
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.*
import javax.annotation.PostConstruct

/**
 * Implementation of [ArtNetworkGalleryClient]
 */
@Component
class ArtNetworkGalleryClientImpl(
    @Autowired private val connectionManager: ConnectionManager
) : ArtNetworkGalleryClient {

    private lateinit var artNetworkGalleryCS: ConnectionService

    // init client and set associated network
    @PostConstruct
    private fun postConstruct() {
        artNetworkGalleryCS = connectionManager.auction
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ArtNetworkGalleryClientImpl::class.java)
    }

    /**
     * Issues an [ArtworkState] representing ownership of the artwork with the id [artworkId], assigned to the gallery.
     *
     * @param galleryParty who will issue/own the artwork
     * @param artworkId a unique UUID to identify the artwork by
     * @param expiry an [Instant] which will default to 3 days from 'now' if not provided
     * @param description of the artwork
     * @param url of the asset/img representing the artwork
     * @return [ArtworkOwnership]
     */
    override fun issueArtwork(galleryParty: ArtworkParty, artworkId: ArtworkId, expiry: Instant?, description: String, url: String): ArtworkOwnership {
        logger.info("Starting IssueArtworkFlow via $galleryParty for $artworkId")
        val state = artNetworkGalleryCS.startFlow(galleryParty, IssueArtworkFlow::class.java, artworkId, expiry, description, url)
        return ArtworkOwnership(
            state.linearId.id,
            state.artworkId,
            state.owner.nameOrNull()!!.toX500Name().toString()
        )
    }

    /**
     * Lists available artwork held by a particular gallery
     *
     * @param galleryParty to query for artwork
     * @return [List][AvailableArtwork]
     */
    override fun listAvailableArtworks(galleryParty: ArtworkParty): List<AvailableArtwork> {
        logger.info("Starting ListAvailableArtworks flow via $galleryParty")
        val artworks = artNetworkGalleryCS.startFlow(galleryParty, FindOwnedArtworksFlow::class.java)
            .map { it.state.data }
        return artworks.map {
            AvailableArtwork(
                it.artworkId,
                it.description,
                it.url,
                listed = true,
                bids = listOf(
                    AvailableArtwork.BidRecord(
                        cordaReference = UUID.randomUUID(),
                        bidderPublicKey = "0xb4bc263278d3ф82a652a8d73a6bfd8ec0ba1a63923bbb4f38147fb8a943da26d",
                        bidderDisplayName = "Bob GBP",
                        amountAndCurrency = Amount(250L, AuctionCurrency.getInstance("GBP")),
                        notary = "O=GBP Notary,L=London,C=GB",
                        expiryDate = Date(),
                        accepted = false
                    )
                )
            )
        }
    }

    /**
     * Award an artwork to a bidder by signing and notarizing an unsigned art transfer transaction,
     * obtaining a [ProofOfTransferOfOwnership]
     *
     * @param galleryParty who holds the artwork
     * @param unsignedArtworkTransferTx byte code representation of the transaction
     * @return [ProofOfTransferOfOwnership] that ownership of the artwork has been transferred.
     */
    override fun finaliseArtworkTransferTx(
        galleryParty: ArtworkParty,
        unsignedArtworkTransferTx: UnsignedArtworkTransferTx
    ): ProofOfTransferOfOwnership {
        logger.info("Starting SignAndFinalizeTransferOfOwnership flow via $galleryParty")
        val unsignedTx: WireTransaction =
            SerializedBytes<WireTransaction>(unsignedArtworkTransferTx.transactionBytes).deserialize()
        val signedTx: SignedTransaction =
            artNetworkGalleryCS.startFlow(galleryParty, SignAndFinalizeTransferOfOwnership::class.java, unsignedTx)
        return ProofOfTransferOfOwnership(
            transactionHash = signedTx.id.toString(),
            notarySignature = TransactionSignature(signedTx.getNotaryTransactionSignature().serialize().bytes)
        )
    }

    /**
     * Get a representation of the ownership of the artwork with id [artworkId] by the gallery [galleryParty]
     */
    override fun getOwnership(galleryParty: ArtworkParty, artworkId: ArtworkId): ArtworkOwnership {
        logger.info("Fetching ownership record for $galleryParty with artworkId: $artworkId")
        return galleryParty.artworkIdToState(artworkId).let {
            ArtworkOwnership(it.linearId.id, it.artworkId, it.owner.nameOrNull().toString())
        }
    }

    /**
     * Query a representation of the ownership of the artwork with id [artworkId]
     *
     * @param artworkId
     * @return [ArtworkOwnership]
     */
    internal fun ArtworkParty.artworkIdToState(artworkId: ArtworkId): ArtworkState {
        logger.info("Fetching ArtworkState for artworkId $artworkId")
        return artNetworkGalleryCS.startFlow(this, FindArtworkFlow::class.java, artworkId)
    }
}