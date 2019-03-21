package com.contractsAndStates.contracts

import com.contractsAndStates.states.*
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.internal.sumByLong
import net.corda.core.transactions.LedgerTransaction
import net.corda.sdk.token.contracts.states.OwnedTokenAmount
import net.corda.sdk.token.contracts.utilities.AMOUNT
import net.corda.sdk.token.contracts.utilities.issuedBy
import net.corda.sdk.token.contracts.utilities.ownedBy
import java.util.ArrayList

// ************************
// * Build Phase Contract *
// ************************

class BuildPhaseContract : Contract {
    companion object {
        const val ID = "com.contractsAndStates.contracts.BuildPhaseContract"
    }

    override fun verify(tx: LedgerTransaction) {

        val command = tx.commands.requireSingleCommand<Commands>()
        val turnTrackerState = tx.inputsOfType<TurnTrackerState>().single()
        val gameBoardState = tx.inputsOfType<GameBoardState>().single()
        val newSettlement = tx.outputsOfType<SettlementState>().single()
        val outputResources = tx.outputsOfType<OwnedTokenAmount<Resource>>()
        val hexTileCoordinate = newSettlement.hexTileCoordinate
        val hexTileIndex = newSettlement.hexTileIndex

        val relevantHexTileNeighbours: ArrayList<HexTile?> = arrayListOf()

        if (hexTileCoordinate != 5) {
            if (gameBoardState.hexTiles[hexTileIndex].sides[hexTileCoordinate - 1] != null) relevantHexTileNeighbours.add(gameBoardState.hexTiles[gameBoardState.hexTiles[hexTileIndex].sides[hexTileCoordinate - 1]!!])
            if (gameBoardState.hexTiles[hexTileIndex].sides[hexTileCoordinate] != null) relevantHexTileNeighbours.add(gameBoardState.hexTiles[gameBoardState.hexTiles[hexTileIndex].sides[hexTileCoordinate]!!])
        } else {
            if (gameBoardState.hexTiles[hexTileIndex].sides[hexTileCoordinate - 1] != null) relevantHexTileNeighbours.add(gameBoardState.hexTiles[gameBoardState.hexTiles[hexTileIndex].sides[hexTileCoordinate - 1]!!])
            if (gameBoardState.hexTiles[hexTileIndex].sides[hexTileCoordinate] != null) relevantHexTileNeighbours.add(gameBoardState.hexTiles[gameBoardState.hexTiles[hexTileIndex].sides[hexTileCoordinate]!!])
        }

        val indexOfRelevantHexTileNeighbour1 = gameBoardState.hexTiles.indexOf(relevantHexTileNeighbours.getOrNull(0))
        val indexOfRelevantHexTileNeighbour2 = gameBoardState.hexTiles.indexOf(relevantHexTileNeighbours.getOrNull(1))

        val resourcesThatShouldBeClaimed = arrayListOf(
                AMOUNT(newSettlement.resourceAmountClaim, Resource.getInstance(gameBoardState.hexTiles[hexTileIndex].resourceType)) issuedBy gameBoardState.players[turnTrackerState.currTurnIndex] ownedBy gameBoardState.players[turnTrackerState.currTurnIndex]
        )
        resourcesThatShouldBeClaimed.add(AMOUNT(newSettlement.resourceAmountClaim, Resource.getInstance(gameBoardState.hexTiles[indexOfRelevantHexTileNeighbour1].resourceType)) issuedBy gameBoardState.players[turnTrackerState.currTurnIndex] ownedBy gameBoardState.players[turnTrackerState.currTurnIndex])
        resourcesThatShouldBeClaimed.add(AMOUNT(newSettlement.resourceAmountClaim, Resource.getInstance(gameBoardState.hexTiles[indexOfRelevantHexTileNeighbour2].resourceType)) issuedBy gameBoardState.players[turnTrackerState.currTurnIndex] ownedBy gameBoardState.players[turnTrackerState.currTurnIndex])

        when (command.value) {

            is Commands.BuildInitialSettlement -> requireThat {

                val turnTracker = tx.inputsOfType<TurnTrackerState>().single()

                if (turnTracker.setUpRound1Complete) {
                    "The player should be issuing them self a resource of the appropriate type" using ( true )
                    if (indexOfRelevantHexTileNeighbour1 != -1) "The player should be issuing them self a resource of the appropriate type" using ( true )
                    if (indexOfRelevantHexTileNeighbour2 != -1) "The player should be issuing them self a resource of the appropriate type" using ( true )
                }

                "A settlement must not have previously been built in this location." using ( !gameBoardState.settlementsPlaced[newSettlement.hexTileIndex][hexTileCoordinate] )
                "A settlement must not have previously been built beside this location." using ( !gameBoardState.settlementsPlaced[newSettlement.hexTileIndex][if (hexTileCoordinate != 0) hexTileCoordinate - 1 else 5] )
                "A settlement must not have previously been built beside this location." using ( !gameBoardState.settlementsPlaced[newSettlement.hexTileIndex][if (hexTileCoordinate != 5) hexTileCoordinate + 1 else 0] )

            }

            is Commands.BuildSettlement -> requireThat {
                val gameBoardState = tx.referenceInputsOfType<GameBoardState>().single()
                val referenceTurnTracker = tx.referenceInputRefsOfType<TurnTrackerState>().single().state.data
                val hexTileCoordinate = newSettlement.hexTileCoordinate
                val wheatInTx = inputResources.filter { it.amount.token.product == Resource.getInstance("Field") }.sumByLong { it.amount.quantity }
                val brickInTx = inputResources.filter { it.amount.token.product == Resource.getInstance("Hill") }.sumByLong { it.amount.quantity }
                val sheepInTx = inputResources.filter { it.amount.token.product == Resource.getInstance("Pasture") }.sumByLong { it.amount.quantity }
                val woodInTx = inputResources.filter { it.amount.token.product == Resource.getInstance("Forest") }.sumByLong { it.amount.quantity }

                "A settlement must not have previously been built in this location." using ( !gameBoardState.settlementsPlaced[newSettlement.hexTileIndex][hexTileCoordinate] )
                "A settlement must not have previously been built beside this location." using ( !gameBoardState.settlementsPlaced[newSettlement.hexTileIndex][if (hexTileCoordinate != 0) hexTileCoordinate - 1 else 5] )
                "A settlement must not have previously been built beside this location." using ( !gameBoardState.settlementsPlaced[newSettlement.hexTileIndex][if (hexTileCoordinate != 5) hexTileCoordinate + 1 else 0] )

                "The player must have provided the appropriate amount of wheat to build a settlement" using ( wheatInTx == 1.toLong())
                "The player must have provided the appropriate amount of brick to build a settlement" using ( brickInTx == 1.toLong())
                "The player must have provided the appropriate amount of ore to build a settlement" using ( sheepInTx == 1.toLong())
                "The player must have provided the appropriate amount of  to build a settlement" using ( woodInTx == 1.toLong())
                "There must be no input settlements" using (tx.inputsOfType<SettlementState>().size == 1)
                "The player must be attempting to build a single settlement" using (tx.outputsOfType<SettlementState>().size == 1)
            }
        }

    }

    interface Commands : CommandData {
        class BuildInitialSettlement: Commands
        class BuildSettlement: Commands
        class BuildCity: Commands
        class BuildRoad: Commands
    }

}