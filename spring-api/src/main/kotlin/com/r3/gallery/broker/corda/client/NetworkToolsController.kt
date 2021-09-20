package com.r3.gallery.broker.corda.client

import com.r3.gallery.api.LogUpdateEntry
import com.r3.gallery.api.NetworkBalancesResponse
import com.r3.gallery.api.Participant
import com.r3.gallery.broker.corda.client.art.api.ArtNetworkGalleryClient
import com.r3.gallery.broker.corda.client.art.controllers.asResponse
import com.r3.gallery.broker.corda.client.token.api.TokenNetworkBuyerClient
import com.r3.gallery.broker.corda.rpc.service.ConnectionServiceImpl
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller with aggregate access across API layers
 * for x-network queries and generic demo operations.
 */
@CrossOrigin
@RestController
@ConditionalOnProperty(prefix = "mock.controller", name = ["enabled"], havingValue = "false")
@RequestMapping("/network")
class NetworkToolsController(
    @Autowired private val networkToolsService: NetworkToolsService,
    @Autowired private val galleryClient: ArtNetworkGalleryClient,
    @Autowired private val tokenClient: TokenNetworkBuyerClient
) {

    companion object {
        private val logger = LoggerFactory.getLogger(NetworkToolsController::class.java)
        const val TIMEOUT = ConnectionServiceImpl.TIMEOUT
    }

    /**
     * REST endpoint for returning participants across all networks.
     *
     * @param networks optional list of networks to filter on
     */
    @GetMapping("/participants")
    fun participants(
        @RequestParam("networks", required = false) networks: List<String>?
    ): ResponseEntity<List<Participant>> {
        logger.info("Request for all participants")
        return asResponse(networkToolsService.participants(networks))
    }

    /**
     * Log returns progressUpdates for Node Level state-machine updates
     *
     * @param index to retrieve a subset of log updates, defaults to returning full set of all updates
     */
    @GetMapping("/log")
    fun log(
        @RequestParam("index", required = false) index: Int?
    ): ResponseEntity<List<LogUpdateEntry>> {
        logger.info("Request for logs")
        return asResponse(networkToolsService.getLogs(index))
    }

    /**
     * Get balances across all parties and networks
     */
    @GetMapping("/balance")
    fun balance(): ResponseEntity<List<NetworkBalancesResponse>> {
        logger.info("Request for balance of parties across network")
        return asResponse(networkToolsService.getBalance())
    }

    /**
     * Initialise the demo by issuing artwork pieces and funds to the demo parties.
     */
    @GetMapping("/init")
    fun initializeDemo(): ResponseEntity<Unit> {
        logger.info("Request for initial issuance to networks.")
        networkToolsService.clearDemo()
        networkToolsService.initializeDemo()
        return asResponse(Unit)
    }
}