package com.r3.gallery.broker.services.api

import com.r3.gallery.api.*

sealed class Receipt {

    abstract val bidderName: String
    abstract val artworkId: ArtworkId
    abstract val amount: Long
    abstract val currency: String

    data class BidReceipt(
        override val bidderName: String,
        override val artworkId: ArtworkId,
        override val amount: Long,
        override val currency: String,
        val unsignedArtworkTransferTx: UnsignedArtworkTransferTx,
        val encumberedTokens: TransactionHash
    ) : Receipt()

    data class SaleReceipt(
        override val bidderName: String,
        override val artworkId: ArtworkId,
        override val amount: Long,
        override val currency: String,
        val transferTxId: TransactionHash,
        val tokenTxId: TransactionHash
    ) : Receipt()

    data class CancellationReceipt(
        override val bidderName: String,
        override val artworkId: ArtworkId,
        override val amount: Long,
        override val currency: String,
        val transferTxId: TransactionHash
    ) : Receipt()
}