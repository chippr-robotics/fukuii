package com.chipprbots.ethereum.ledger

import com.chipprbots.ethereum.vm.VM

object LocalVM extends VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage]
