package com.r3.gallery.workflows

import co.paralleluniverse.fibers.Suspendable
import com.r3.gallery.contracts.ArtworkContract
import com.r3.gallery.states.ArtworkState
import com.r3.gallery.states.ValidatedDraftTransferOfOwnership
import com.r3.gallery.utils.generateWireTransactionMerkleTree
import com.r3.gallery.utils.getDependencies
import net.corda.core.contracts.TimeWindow
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.MerkleTree
import net.corda.core.crypto.SignatureMetadata
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap

@InitiatingFlow
@StartableByRPC
class RequestDraftTransferOfOwnershipFlow(
    val galleryParty: Party,
    val artworkLinearId: UniqueIdentifier
) : FlowLogic<Pair<WireTransaction, ValidatedDraftTransferOfOwnership>>() {

    @Suppress("ClassName")
    companion object {
        object REQUESTING_DRAFTTX : ProgressTracker.Step("Requesting draft transfer transaction from gallery")
        object VERIFYING_DRAFTTX : ProgressTracker.Step("Verifying gallery's draft transaction and its dependencies")
        object VERIFYING_NOTARY_IDENTITY : ProgressTracker.Step("Verifying draft transaction controlling notary params to relay to token's network sibling node")
    }

    override val progressTracker = ProgressTracker(
        REQUESTING_DRAFTTX,
        VERIFYING_DRAFTTX,
        VERIFYING_NOTARY_IDENTITY
    )


    @Suspendable
    override fun call(): Pair<WireTransaction, ValidatedDraftTransferOfOwnership> {

        progressTracker.currentStep = REQUESTING_DRAFTTX
        val session = initiateFlow(galleryParty)
        val wireTx = session.sendAndReceive<WireTransaction>(artworkLinearId).unwrap { it }

        progressTracker.currentStep = VERIFYING_DRAFTTX
        val txMerkleTree = wireTx.generateWireTransactionMerkleTree()
        val txOk = receiveAndVerifyTxDependencies(session, wireTx) && verifyShareConditions(wireTx, txMerkleTree)
                && verifySharedTx(wireTx)

        if (!txOk) {
            throw FlowException("Failed to validate the proposed transaction or one of its dependencies")
        }

        progressTracker.currentStep = VERIFYING_NOTARY_IDENTITY
        val notaryIdentity = serviceHub.identityService.partyFromKey(wireTx.notary!!.owningKey)
            ?: throw IllegalArgumentException("Unable to retrieve party for notary key: ${wireTx.notary!!.owningKey}")
        val notaryInfo = serviceHub.networkMapCache.getNodeByLegalIdentity(notaryIdentity)
            ?: throw IllegalArgumentException("Unable to retrieve notaryInfo for notary: $notaryIdentity")
        val signatureMetadata = SignatureMetadata(
            notaryInfo.platformVersion,
            Crypto.findSignatureScheme(notaryIdentity.owningKey).schemeNumberID
        )

        return Pair(wireTx, ValidatedDraftTransferOfOwnership(wireTx, notaryIdentity, signatureMetadata))
    }

    @Suspendable
    private fun receiveAndVerifyTxDependencies(otherSession: FlowSession, wireTransaction: WireTransaction): Boolean {
        return wireTransaction.getDependencies().all {
            try {
                subFlow(ReceiveTransactionFlow(otherSession))
                true
            } catch (e: Exception) {
                logger.warn("Failed to resolve input transaction ${it.toHexString()}: ${e.message}")
                false
            }
        }
    }

    @Suspendable
    private fun verifyShareConditions(wireTransaction: WireTransaction, expectedMerkleTree: MerkleTree): Boolean {
        val id = wireTransaction.id
        val suppliedMerkleTree = wireTransaction.merkleTree
        val timeWindow = wireTransaction.timeWindow
        val notary = wireTransaction.notary

        return !listOf(
            (expectedMerkleTree != suppliedMerkleTree) to
                    "The supplied merkle tree ($suppliedMerkleTree) did not match the expected merkle tree ($expectedMerkleTree)",
            (id != suppliedMerkleTree.hash) to
                    "The supplied merkle tree hash (${suppliedMerkleTree.hash}) did not match the supplied id (${id})",
            (timeWindow == null) to
                    "Time window must be provided",
            (notary == null) to
                    "Notary must be provided"
        ).any {
            if (it.first) {
                logger.warn("Failed to process shared transaction $id: ${it.second}")
            }
            it.first
        }
    }

    @Suspendable
    private fun verifySharedTx(wireTransaction: WireTransaction): Boolean {
        val ledgerTx = wireTransaction.toLedgerTransaction(serviceHub)
        return try {
            ledgerTx.verify()
            true
        } catch (e: Exception) {
            logger.warn("Failed to resolve transaction: ${e.message}")
            false
        }
    }
}

@InitiatedBy(RequestDraftTransferOfOwnershipFlow::class)
class RequestDraftTransferOfOwnershipFlowHandler(val otherSession: FlowSession) : FlowLogic<Unit>() {

    @Suppress("ClassName")
    companion object {
        object RECEIVING_DRAFTTX_REQUEST : ProgressTracker.Step("Receiving draft transfer transaction request from bidder.")
        object ARTWORK_LOOKUP : ProgressTracker.Step("Looking up artwork item to draft transaction for.")
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on requested artwork item.")
        object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
        object SENDING_UNSIGNED_TX : ProgressTracker.Step("Sending unsigned draft transfer transaction to bidder.")
        object SENDING_DEPENDENCIES : ProgressTracker.Step("Sending draft transfer transaction's dependencies.")
    }

    override val progressTracker = ProgressTracker(
        RECEIVING_DRAFTTX_REQUEST,
        ARTWORK_LOOKUP,
        GENERATING_TRANSACTION,
        VERIFYING_TRANSACTION,
        SENDING_UNSIGNED_TX,
        SENDING_DEPENDENCIES
    )

    @Suspendable
    override fun call() {

        progressTracker.currentStep = RECEIVING_DRAFTTX_REQUEST
        val bidderParty = otherSession.counterparty
        val artworkLinearId = otherSession.receive<UniqueIdentifier>().unwrap { it }

        progressTracker.currentStep = ARTWORK_LOOKUP
        val artworkStates = serviceHub.vaultService.queryBy(ArtworkState::class.java)
        val artworkStateAndRef =
            // HACK: we look-up for both IDs to overcome some design/implementation issues
            requireNotNull(artworkStates.states.singleOrNull { it.state.data.linearId == artworkLinearId || it.state.data.artworkId == artworkLinearId.id }) {
                "Unable to find an artwork state by the id: $artworkLinearId"
            }

        progressTracker.currentStep = GENERATING_TRANSACTION
        val artworkState = artworkStateAndRef.state.data
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val wireTx = with(TransactionBuilder(notary)) {
            addInputState(artworkStateAndRef)
            addOutputState(artworkState.withNewOwner(bidderParty), ArtworkContract.ARTWORKCONTRACTID)
            addCommand(ArtworkContract.Commands.TransferOwnership(), ourIdentity.owningKey, bidderParty.owningKey)
            setTimeWindow(TimeWindow.untilOnly(artworkState.expiry))
        }.also {
            progressTracker.currentStep = VERIFYING_TRANSACTION
            it.verify(serviceHub)
        }.toWireTransaction(serviceHub)

        progressTracker.currentStep = SENDING_UNSIGNED_TX
        otherSession.send(wireTx)

        progressTracker.currentStep = SENDING_DEPENDENCIES
        val txDependencies = wireTx.getDependencies()
        txDependencies.forEach {
            val validatedTxDependency = serviceHub.validatedTransactions.getTransaction(it)
                ?: throw FlowException("Unable to find validated transaction for input: $it")

            subFlow(SendTransactionFlow(otherSession, validatedTxDependency))
        }
    }
}
