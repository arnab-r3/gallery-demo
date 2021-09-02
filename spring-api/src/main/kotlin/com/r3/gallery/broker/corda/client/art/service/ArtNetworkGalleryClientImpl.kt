package com.r3.gallery.broker.corda.client.art.service

import com.r3.gallery.api.*
import com.r3.gallery.broker.corda.client.art.api.ArtNetworkGalleryClient
import com.r3.gallery.broker.corda.client.config.ClientProperties
import com.r3.gallery.states.ArtworkState
import com.r3.gallery.utils.getNotaryTransactionSignature
import com.r3.gallery.workflows.CreateDraftTransferOfOwnershipFlow
import com.r3.gallery.workflows.SignAndFinalizeTransferOfOwnership
import com.r3.gallery.workflows.artwork.FindArtworkFlow
import com.r3.gallery.workflows.artwork.FindOwnedArtworksFlow
import com.r3.gallery.workflows.artwork.IssueArtworkFlow
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.transactions.WireTransaction
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.*

@Component
class ArtNetworkGalleryClientImpl(
    @Autowired
    @Qualifier("ArtNetworkGalleryProperties")
    clientProperties: ClientProperties
) : NodeClient(clientProperties), ArtNetworkGalleryClient {

    companion object {
        private val logger = LoggerFactory.getLogger(ArtNetworkGalleryClientImpl::class.java)
    }

    /**
     * Create a state representing ownership of the artwork with the id [artworkId], assigned to the gallery.
     */
    override suspend fun issueArtwork(galleryParty: ArtworkParty, artworkId: ArtworkId) : ArtworkOwnership {
        logger.info("Starting IssueArtworkFlow via $galleryParty for $artworkId")
        return galleryParty.network().startFlow(IssueArtworkFlow::class.java, artworkId)
    }

    /**
     * List out the artworks still held by the gallery.
     */
    override suspend fun listAvailableArtworks(galleryParty: ArtworkParty): List<ArtworkId> {
        logger.info("Starting ListAvailableArtworks flow via $galleryParty")
        return galleryParty.network().startFlow(FindOwnedArtworksFlow::class.java).map { it.state.data.artworkId }
    }

    /**
     * Create an unsigned transaction that would transfer an artwork owned by the gallery,
     * identified by [galleryOwnership], to the given bidder.
     *
     * @return The unsigned fulfilment transaction
     */
    override suspend fun createArtworkTransferTx(galleryParty: ArtworkParty, bidderParty: ArtworkParty, galleryOwnership: ArtworkOwnership): UnsignedArtworkTransferTx {
        logger.info("Starting CreateArtworkTransferTx flow via $galleryParty with bidder: $bidderParty for ownership $galleryOwnership")
        val partyToTransferTo = galleryParty.network().wellKnownPartyFromName(bidderParty)
        val artworkLinearId = UniqueIdentifier.fromString(galleryOwnership.cordaReference.toString())
        val unsignedTx = galleryParty.network().startFlow(CreateDraftTransferOfOwnershipFlow::class.java, artworkLinearId, partyToTransferTo)
        return UnsignedArtworkTransferTx(unsignedTx.serialize().bytes)
    }
    /**
     * Award an artwork to a bidder by signing and notarizing an unsigned art transfer transaction,
     * obtaining a [ProofOfTransferOfOwnership]
     *
     * @return Proof that ownership of the artwork has been transferred.
     */
    override suspend fun finaliseArtworkTransferTx(galleryParty: ArtworkParty, unsignedArtworkTransferTx: UnsignedArtworkTransferTx): ProofOfTransferOfOwnership {
        logger.info("Starting finaliseArtworkTransferTx flow via $galleryParty")
        val unsignedTx = SerializedBytes<WireTransaction>(unsignedArtworkTransferTx.transactionBytes).deserialize()
        val signedTx = galleryParty.network().startFlow(SignAndFinalizeTransferOfOwnership::class.java, unsignedTx)
        return ProofOfTransferOfOwnership(
            transactionId = UUID.randomUUID(),
            transactionHash = TransactionHash(),
            previousOwnerSignature = TransactionSignature(ByteArray(0)),
            notarySignature = TransactionSignature(signedTx.getNotaryTransactionSignature().bytes),
        )
    }

    /**
     * Get a representation of the ownership of the artwork with id [artworkId] by the gallery [galleryParty]
     */
    override suspend fun getOwnership(galleryParty: ArtworkParty, artworkId: ArtworkId): ArtworkOwnership {
        logger.info("Fetching ownership record for $galleryParty with artworkId: $artworkId")
        return galleryParty.artworkIdToState(artworkId).let {
            ArtworkOwnership(it.linearId.id, it.artworkId, it.owner.nameOrNull().toString())
        }
    }

    /**
     * Simple shorthand for describing connection id in terms of node vs network
     */
    internal fun ArtworkParty.network() : RPCConnectionId
        = (this + CordaRPCNetwork.AUCTION.toString())
            .also { idExists(it) } // check validity

    /**
     * Returns the ArtworkState associated with the ArtworkId
     */
    internal fun ArtworkParty.artworkIdToState(artworkId: ArtworkId): ArtworkState {
        logger.info("Fetching ArtworkState for artworkId $artworkId")
        return network().startFlow(FindArtworkFlow::class.java, artworkId)
    }

    /**
     * Returns the ArtworkState associated with the CordaReference
     */
    internal fun ArtworkParty.artworkIdToCordaReference(artworkId: ArtworkId): CordaReference {
        return artworkIdToState(artworkId).linearId.id
    }
}