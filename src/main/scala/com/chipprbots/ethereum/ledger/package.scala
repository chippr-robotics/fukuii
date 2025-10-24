package com.chipprbots.ethereum

import com.chipprbots.ethereum.vm.ProgramContext
import com.chipprbots.ethereum.vm.ProgramResult
import com.chipprbots.ethereum.vm.VM

package object ledger {
  type VMImpl = VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage]
  type PC = ProgramContext[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage]
  type PR = ProgramResult[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage]
}
