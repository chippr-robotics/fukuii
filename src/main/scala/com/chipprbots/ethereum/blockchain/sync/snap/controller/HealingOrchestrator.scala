package com.chipprbots.ethereum.blockchain.sync.snap.controller

private[snap] trait PivotRefreshApi:
  def refreshPivotInPlace(
      reason: String,
      countsTowardHealBudget: Boolean = true,
      pivotUnservable: Boolean = false
  ): Unit
