package com.flows

import co.paralleluniverse.fibers.Suspendable
import com.contractsAndStates.contracts.TurnTrackerContract
import com.contractsAndStates.states.GameBoardState
import com.contractsAndStates.states.TurnTrackerState
import net.corda.core.contracts.Command
import net.corda.core.contracts.ReferencedStateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.lang.IllegalArgumentException
import javax.management.Query

// *****************
// * End Turn Flow *
// *****************

/**
 * This flow allows the player to end their turn, update the turn tracker. It also
 * signals to the next player that it is now their turn.
 */

@InitiatingFlow(version = 1)
@StartableByRPC
class EndTurnFlow(val gameBoardStateLinearId: UniqueIdentifier): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {

        // Step 1. Get a reference to the notary
        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        // Step 2. Retrieve the TurnTracker State from the vault.
        val gameBoardStateQueryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(gameBoardStateLinearId))
        val gameBoardStateAndRef = serviceHub.vaultService.queryBy<GameBoardState>(gameBoardStateQueryCriteria).states.first()
        val gameBoardState = gameBoardStateAndRef.state.data

        // Step 3. Retrieve the TurnTracker State from the vault.
        val turnTrackerStateQueryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(gameBoardState.turnTrackerLinearId))
        val turnTrackerStateAndRef = serviceHub.vaultService.queryBy<TurnTrackerState>(turnTrackerStateQueryCriteria).states.first()
        val turnTrackerState = turnTrackerStateAndRef.state.data

        // Step 4. Create a new transaction builder using the notary
        val tb = TransactionBuilder(notary)

        // Step 5. Create a new command for ending our turns
        val endTurnCommand = Command(TurnTrackerContract.Commands.EndTurn(), gameBoardState.players.map { it.owningKey })
        tb.addCommand(endTurnCommand)

        // Step 6. Add the old turn tracker state as an input.
        tb.addInputState(turnTrackerStateAndRef)

        // Step 7. Create the new turn tracker state and add it as an output.
        tb.addOutputState(turnTrackerState.endTurn())

        // Step 8. Add the game board state as a reference
        tb.addReferenceState(ReferencedStateAndRef(gameBoardStateAndRef))

        // Step 9. Verify and then sign the initial transaction
        tb.verify(serviceHub)
        val ptx = serviceHub.signInitialTransaction(tb)

        // Step 10. Gather signatures from relevant parties (all other players)
        val sessions = (gameBoardState.players - ourIdentity).map { initiateFlow(it) }.toSet()
        val stx = subFlow(CollectSignaturesFlow(ptx, sessions))

        // Step 11. Run the FinalityFlow
        return subFlow(FinalityFlow(stx, sessions))

    }
}

@InitiatedBy(EndTurnFlow::class)
class EndTurnFlowResponder(val counterpartySession: FlowSession): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signedTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) {
                val inputTurnTrackerInTransaction = stx.coreTransaction.inputs.single()
                val queryCriteria = QueryCriteria.VaultQueryCriteria(stateRefs = listOf(inputTurnTrackerInTransaction))
                val turnTrackerReferencedInTransaction = serviceHub.vaultService.queryBy<TurnTrackerState>(queryCriteria).states.single().state.data

                val gameBoardStateRef = stx.coreTransaction.references.single()
                val gameBoard = serviceHub.vaultService.queryBy<GameBoardState>(QueryCriteria.VaultQueryCriteria(stateRefs = listOf(gameBoardStateRef))).states.single().state.data
                val queryCriteriaForLastTurnTrackerWeHaveOnRecord = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(gameBoard.turnTrackerLinearId))
                val lastTurnTrackerWeHaveOnRecord = serviceHub.vaultService.queryBy<TurnTrackerState>(queryCriteriaForLastTurnTrackerWeHaveOnRecord).states.single().state.data
                if (lastTurnTrackerWeHaveOnRecord.linearId != turnTrackerReferencedInTransaction.linearId) {
                    throw IllegalArgumentException("The TurnTracker included in the transaction is not correct for this game or turn.")
                }
                if (lastTurnTrackerWeHaveOnRecord.currTurnIndex != turnTrackerReferencedInTransaction.currTurnIndex) {
                    throw IllegalArgumentException("The player is proposing an incorrect turn advancement.")
                }
                if (gameBoard.players[turnTrackerReferencedInTransaction.currTurnIndex] != counterpartySession.counterparty) {
                    throw IllegalArgumentException("The player who is proposing this transaction is not currently the player whose turn it is.")
                }
            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)

        return subFlow(ReceiveFinalityFlow(otherSideSession = counterpartySession, expectedTxId = txWeJustSignedId.id))
    }
}
