package com.flows

import co.paralleluniverse.fibers.Suspendable
import com.contractsAndStates.states.GameBoardState
import com.contractsAndStates.states.TurnTrackerState
import com.oracleClient.contracts.DiceRollContract
import com.oracleClient.flows.GetRandomDiceRollValues
import com.oracleClient.state.DiceRollState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

// ******************
// * Roll Dice Flow *
// ******************

@InitiatingFlow
@StartableByRPC
class RollDiceFlow(val gameBoardStateLinearId: UniqueIdentifier) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {

        val queryCriteriaForGameBoardState = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(gameBoardStateLinearId))
        val gameBoardStateAndRef = serviceHub.vaultService.queryBy<GameBoardState>(queryCriteriaForGameBoardState).states.single()
        val gameBoardState = gameBoardStateAndRef.state.data

        val queryCriteriaForTurnTrackerState = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(gameBoardState.turnTrackerLinearId))
        val turnTrackerState = serviceHub.vaultService.queryBy<TurnTrackerState>(queryCriteriaForTurnTrackerState).states.first().state.data
        val turnTrackerStateLinearId = turnTrackerState.linearId

        val oracleLegalName = CordaX500Name("Oracle", "New York", "US")
        val oracle = serviceHub.networkMapCache.getNodeByLegalName(oracleLegalName)!!.legalIdentities.single()

        val diceRoll = subFlow(GetRandomDiceRollValues(turnTrackerStateLinearId, gameBoardState.linearId, gameBoardState.players, oracle))
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val tb = TransactionBuilder(notary)

        tb.addOutputState(DiceRollState(diceRoll))
        tb.addCommand(DiceRollContract.Commands.RollDice(), gameBoardState.players.map { it.owningKey })

        tb.verify(serviceHub)
        val ptx = serviceHub.signInitialTransaction(tb)

        val sessions = (gameBoardState.players - ourIdentity).toSet().map { initiateFlow(it) }
        val stx = subFlow(CollectSignaturesFlow(ptx, sessions))

        return subFlow(FinalityFlow(stx, sessions))
    }
}

@InitiatedBy(RollDiceFlow::class)
class RollDiceFlowResponder(val counterpartySession: FlowSession): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signedTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) {
                val diceRollState = stx.coreTransaction.outputsOfType<DiceRollState>().first()
                val gameBoardState = serviceHub.vaultService.queryBy<GameBoardState>().states.first().state.data
                val turnTrackerState = serviceHub.vaultService.queryBy<TurnTrackerState>().states.first().state.data

                if (diceRollState.turnTrackerUniqueIdentifier != turnTrackerState.linearId) {
                    throw FlowException("Only the current player may roll the dice.")
                }

                if (diceRollState.gameBoardStateUniqueIdentifier != gameBoardState.linearId) {
                    throw FlowException("The dice roll must have been generated for this game.")
                }

                val oracle = serviceHub.networkMapCache.getNodeByLegalName(CordaX500Name("Oracle", "New York", "US"))
                if (diceRollState.signedDataWithOracleCert.sig.by != oracle!!.legalIdentitiesAndCerts.first().certificate) {
                    throw FlowException("This dice roll was not generated by the oracle")
                }

            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)
        return subFlow(ReceiveFinalityFlow(otherSideSession = counterpartySession, expectedTxId = txWeJustSignedId.id))
    }
}