package com.oracleService.flows

import co.paralleluniverse.fibers.Suspendable
import com.flows.RollDiceFlow
import com.flows.RollDiceFlowResponder
import com.oracleClient.state.DiceRollState
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction

@InitiatedBy(RollDiceFlow::class)
class OracleRollDiceFlowResponder(val counterpartySession: FlowSession): RollDiceFlowResponder(counterpartySession) {
    @Suspendable
    override fun call(): SignedTransaction {
        val signedTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) {

                val diceRollState = stx.coreTransaction.outputsOfType<DiceRollState>().first()

                if (diceRollState.signedDataWithOracleCert.sig.by != ourIdentityAndCert.certificate) {
                    throw FlowException("This dice roll was not generated by the oracle")
                }

                if (serviceHub.vaultService.queryBy(DiceRollState::class.java).states.filter { it.state.data.turnTrackerUniqueIdentifier == diceRollState.turnTrackerUniqueIdentifier }.isNotEmpty()) {
                    throw FlowException("A dice roll has previously been generated for this turn")
                }

            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)
        return subFlow(ReceiveFinalityFlow(otherSideSession = counterpartySession, expectedTxId = txWeJustSignedId.id))
    }
}