package com.oracleFlowTests

import com.contractsAndStates.states.GameBoardState
import com.flows.*
import com.oracleClient.state.DiceRollState
import com.oracleService.flows.DiceRollRequestHandler
import com.oracleService.flows.OracleRollDiceFlowResponder
import com.testUtilities.placeAPieceFromASpecificNodeAndEndTurn
import com.testUtilities.setupGameBoardForTesting
import net.corda.core.identity.CordaX500Name
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.internal.chooseIdentity
import net.corda.testing.node.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class OracleClientIntegrationTest {
    private val network = MockNetwork(listOf("com.contractsAndStates", "com.flows", "com.oracleClient", "com.r3.corda.sdk.token.workflows", "com.r3.corda.sdk.token.contracts", "com.r3.corda.sdk.token.money"),
            notarySpecs = listOf(MockNetworkNotarySpec(CordaX500Name("Notary", "London", "GB"))),
            defaultParameters = MockNetworkParameters(networkParameters = testNetworkParameters(minimumPlatformVersion = 4))
    )
    private val a = network.createNode(MockNodeParameters())
    private val b = network.createNode(MockNodeParameters())
    private val c = network.createNode(MockNodeParameters())
    private val d = network.createNode(MockNodeParameters())
    private val oracleName = CordaX500Name("Oracle", "New York", "US")
    private val oracle = network.createNode(
            MockNodeParameters(legalName = oracleName).withAdditionalCordapps(
                    listOf(
                            TestCordapp.findCordapp("com.oracleService")
                    )
            )
    )

    @Before
    fun setup() {
        val startedNodes = arrayListOf(a, b, c, d)

        startedNodes.forEach {
            it.registerInitiatedFlow(SetupGameBoardFlowResponder::class.java)
            it.registerInitiatedFlow(BuildInitialSettlementFlowResponder::class.java)
            it.registerInitiatedFlow(GatherResourcesFlowResponder::class.java)
            it.registerInitiatedFlow(RollDiceFlowResponder::class.java)
        }

        listOf(
                DiceRollRequestHandler::class.java,
                OracleRollDiceFlowResponder::class.java
        ).forEach { oracle.registerInitiatedFlow(it) }

        network.runNetwork()
    }

    @After
    fun tearDown() = network.stopNodes()

    @Test
    fun oracleReturnsARandomDiceRoll() {

        // Get an identity for each of the players of the game.
        val p1 = a.info.chooseIdentity()
        val p2 = b.info.chooseIdentity()
        val p3 = c.info.chooseIdentity()
        val p4 = d.info.chooseIdentity()

        // Issue a game state onto the ledger
        val gameStateIssueFlow = (SetupGameBoardFlow(p1, p2, p3, p4))
        val futureWithGameState = a.startFlow(gameStateIssueFlow)
        network.runNetwork()

        val stxGameState = futureWithGameState.getOrThrow()

        // Get a reference to the issued game state
        val gameState = stxGameState.coreTransaction.outputsOfType<GameBoardState>().single()

        val arrayOfAllTransactions = arrayListOf<SignedTransaction>()
        val arrayOfAllPlayerNodes = arrayListOf(a, b, c, d)
        val arrayOfAllPlayerNodesInOrder = gameState.players.map { player -> arrayOfAllPlayerNodes.filter { it.info.chooseIdentity() == player }.first() }

        setupGameBoardForTesting(gameState, network, arrayOfAllPlayerNodesInOrder, arrayOfAllTransactions)

        val gameBoardState = arrayOfAllTransactions.last().coreTransaction.outRefsOfType<GameBoardState>().first().state.data

        val rollDiceFlow = RollDiceFlow(gameBoardState.linearId)
        val futureWithDiceRoll = arrayOfAllPlayerNodesInOrder[0].startFlow(rollDiceFlow)
        network.runNetwork()
        val txWithDiceRoll = futureWithDiceRoll.getOrThrow()
        val diceRollState = txWithDiceRoll.coreTransaction.outputsOfType<DiceRollState>().single()

        val diceRoll1 = diceRollState.randomRoll1
        val diceRoll2 = diceRollState.randomRoll2
        assert(diceRoll1 in 1..7)
        assert(diceRoll2 in 1..7)

    }

}
