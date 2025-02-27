package com.flows

import co.paralleluniverse.fibers.Suspendable
import com.contractsAndStates.contracts.GatherPhaseContract
import com.contractsAndStates.states.*
import com.oracleClient.contracts.DiceRollContract
import com.oracleClient.state.DiceRollState
import com.r3.corda.sdk.token.contracts.FungibleTokenContract
import com.r3.corda.sdk.token.contracts.commands.IssueTokenCommand
import com.r3.corda.sdk.token.contracts.utilities.heldBy
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

// ********************
// * Issue Trade Flow *
// ********************

/**
 * This flow allows nodes to self-issue tokens where they are able to prove that
 * they have the appropriate settlement states to enable them to do so.
 */

@InitiatingFlow(version = 1)
@StartableByRPC
class GatherResourcesFlow(val gameBoardLinearId: UniqueIdentifier): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        // Step 1. Get reference to the notary and oracle
        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        // Step 2. Retrieve the Game Board State from the vault.
        val queryCriteriaForGameBoardState = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(gameBoardLinearId))
        val gameBoardStateAndRef = serviceHub.vaultService.queryBy<GameBoardState>(queryCriteriaForGameBoardState).states.single()
        val gameBoardState = gameBoardStateAndRef.state.data

        // Step 3. Retrieve the Turn Tracker State from the vault
        val queryCriteriaForTurnTrackerState = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(gameBoardState.turnTrackerLinearId))
        val turnTrackerStateAndRef = serviceHub.vaultService.queryBy<TurnTrackerState>(queryCriteriaForTurnTrackerState).states.single()

        // Step 4. Retrieve the Dice Roll State from the vault
        val diceRollStateAndRef = serviceHub.vaultService.queryBy<DiceRollState>().states.single()
        val diceRollState = diceRollStateAndRef.state.data

        // Step 5. Create a transaction builder
        val tb = TransactionBuilder(notary = notary)

        // Step 6. Generate valid settlements for which we will be issuing resources.
        val listOfValidSettlements = serviceHub.vaultService.queryBy<SettlementState>().states.filter {
            val diceRollTotal = diceRollState.randomRoll1 + diceRollState.randomRoll2
            val adjacentHexTileIndex1 = gameBoardState.hexTiles[(gameBoardState.hexTiles[it.state.data.hexTileIndex].sides[it.state.data.hexTileCoordinate]) ?: 7].roleTrigger == diceRollTotal
            val adjacentHexTileIndex2 = gameBoardState.hexTiles[(gameBoardState.hexTiles[it.state.data.hexTileIndex].sides[if (it.state.data.hexTileCoordinate - 1 < 0) 5 else it.state.data.hexTileCoordinate - 1])?: 7].roleTrigger == diceRollTotal
            val primaryHexTile = gameBoardState.hexTiles[it.state.data.hexTileIndex].roleTrigger == diceRollTotal
            adjacentHexTileIndex1 || adjacentHexTileIndex2 || primaryHexTile
        }

        // Step 7. Generate a list of all currencies amounts that will be issued.
        val gameCurrenciesToClaim = arrayListOf<GameCurrencyToClaim>()
        for (result in listOfValidSettlements) {
            val settlementState = result.state.data
            val hexTile = gameBoardState.hexTiles[result.state.data.hexTileIndex]
            val ownerIndex = gameBoardState.players.indexOf(settlementState.owner)
            gameCurrenciesToClaim.add(GameCurrencyToClaim(hexTile.resourceType, ownerIndex))
        }

        // Step 8. Consolidate the list so that they is only one instance of a given token type issued with the appropriate amount.
        // This is required right now as multiple issuances of the same token type cause an error with transaction state grouping.
        val reducedListOfGameCurrencyToClaim = mutableMapOf<GameCurrencyToClaim, Int>()
        gameCurrenciesToClaim.forEach{
            if (reducedListOfGameCurrencyToClaim.containsKey(it)) reducedListOfGameCurrencyToClaim[it] = reducedListOfGameCurrencyToClaim[it]!!.plus(1)
            else reducedListOfGameCurrencyToClaim[it] = 1
        }

        // Step 9. Convert each gameCurrentToClaim into a valid fungible token.
        val fungibleTokenAmountsOfResourcesToClaim = reducedListOfGameCurrencyToClaim.map {
            amount(it.value, Resource.getInstance(it.key.resourceType)) issuedBy ourIdentity heldBy gameBoardState.players[it.key.ownerIndex]
        }

        // Step 10. Add commands to issue the appropriate types of resources. Convert the gameCurrencyToClaim to a set to prevent duplicate commands.
        val resourceTypesToBeIssuedForWhichACommandShouldBeIncluded = gameCurrenciesToClaim.toSet()
        resourceTypesToBeIssuedForWhichACommandShouldBeIncluded.forEach {
            tb.addCommand(IssueTokenCommand(Resource.getInstance(it.resourceType) issuedBy ourIdentity), gameBoardState.players.map { it.owningKey })
        }

        // Step 11. Add all of the new owned token amounts to the transaction.
        fungibleTokenAmountsOfResourcesToClaim.forEach {
            tb.addOutputState(it, FungibleTokenContract.contractId)
        }

        // Step 12. Add reference states for turn tracker and game board. Add the dice roll as an input
        // state so that the counter-party may verify the correct number of resources have been issued.
        tb.addInputState(diceRollStateAndRef)
        tb.addReferenceState(ReferencedStateAndRef(gameBoardStateAndRef))
        tb.addReferenceState(ReferencedStateAndRef(turnTrackerStateAndRef))

        // Step 13. Add the gather resources command and verify the transaction
        val commandSigners = gameBoardState.players.map {it.owningKey}
        tb.addCommand(GatherPhaseContract.Commands.IssueResourcesToAllPlayers(), commandSigners)
        tb.addCommand(DiceRollContract.Commands.ConsumeDiceRoll(), commandSigners)
        tb.verify(serviceHub)

        // Step 14. Collect the signatures and sign the transaction
        val ptx = serviceHub.signInitialTransaction(tb)
        val sessions = (gameBoardState.players - ourIdentity).toSet().map { initiateFlow(it) }
        val stx = subFlow(CollectSignaturesFlow(ptx, sessions))
        return subFlow(FinalityFlow(stx, sessions))
    }

}

@InitiatedBy(GatherResourcesFlow::class)
open class GatherResourcesFlowResponder(val counterpartySession: FlowSession): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signedTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val listOfTokensIssued = stx.coreTransaction.outputsOfType<FungibleState<*>>().toMutableList()
                val gameBoardState = serviceHub.vaultService.queryBy<GameBoardState>(QueryCriteria.VaultQueryCriteria(stateRefs = stx.references)).states.single().state.data
                val turnTrackerState = serviceHub.vaultService.queryBy<TurnTrackerState>(QueryCriteria.LinearStateQueryCriteria(linearId = listOf(gameBoardState.turnTrackerLinearId))).states.single().state.data
                val diceRollState = serviceHub.vaultService.queryBy<DiceRollState>(QueryCriteria.VaultQueryCriteria(stateRefs = stx.inputs)).states.single().state.data
                val listOfValidSettlements = serviceHub.vaultService.queryBy<SettlementState>().states.filter {
                    val diceRollTotal = diceRollState.randomRoll1 + diceRollState.randomRoll2
                    val adjacentHexTileIndex1 = gameBoardState.hexTiles[(gameBoardState.hexTiles[it.state.data.hexTileIndex].sides[it.state.data.hexTileCoordinate]) ?: 7].roleTrigger == diceRollTotal
                    val adjacentHexTileIndex2 = gameBoardState.hexTiles[(gameBoardState.hexTiles[it.state.data.hexTileIndex].sides[if (it.state.data.hexTileCoordinate - 1 < 0) 5 else it.state.data.hexTileCoordinate - 1]) ?: 7].roleTrigger == diceRollTotal
                    val primaryHexTile = gameBoardState.hexTiles[it.state.data.hexTileIndex].roleTrigger == diceRollTotal
                    adjacentHexTileIndex1 || adjacentHexTileIndex2 || primaryHexTile
                }

                val gameCurrenciesThatShouldHaveBeenClaimed = arrayListOf<GameCurrencyToClaim>()
                for (result in listOfValidSettlements) {
                    val settlementState = result.state.data
                    val hexTile = gameBoardState.hexTiles[result.state.data.hexTileIndex]
                    val ownerIndex = gameBoardState.players.indexOf(settlementState.owner)
                    gameCurrenciesThatShouldHaveBeenClaimed.add(GameCurrencyToClaim(hexTile.resourceType, ownerIndex))
                }

                // Consolidate the list so that they is only one instance of a given token type issued with the appropriate amount.
                val reducedListOfGameCurrencyToClaim = mutableMapOf<GameCurrencyToClaim, Int>()
                gameCurrenciesThatShouldHaveBeenClaimed.forEach{
                    if (reducedListOfGameCurrencyToClaim.containsKey(it)) reducedListOfGameCurrencyToClaim[it] = reducedListOfGameCurrencyToClaim[it]!!.plus(1)
                    else reducedListOfGameCurrencyToClaim[it] = 1
                }

                // Convert each gameCurrentToClaim into a valid fungible token.
                val listOfTokensThatShouldHaveBeenIssued = reducedListOfGameCurrencyToClaim.map {
                    amount(it.value, Resource.getInstance(it.key.resourceType)) issuedBy gameBoardState.players[turnTrackerState.currTurnIndex] heldBy gameBoardState.players[it.key.ownerIndex]
                }

                "The correct number of resources must be produced for each respective party" using (listOfTokensThatShouldHaveBeenIssued.filter {
                    listOfTokensIssued.indexOf(it) == -1
                }.isEmpty() && listOfTokensIssued.size == listOfTokensThatShouldHaveBeenIssued.size)

            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)

        return subFlow(ReceiveFinalityFlow(otherSideSession = counterpartySession, expectedTxId = txWeJustSignedId.id))
    }

}

data class GameCurrencyToClaim(
        val resourceType: String,
        val ownerIndex: Int
)
