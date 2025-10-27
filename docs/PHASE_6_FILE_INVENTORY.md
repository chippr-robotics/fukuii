# Phase 6: Test & Integration Migration - File Inventory

**Generated**: 2025-10-27  
**Total Files Remaining**: 92 files with Monix imports  
**Progress**: 33 of 125 files migrated (26%)

## Overview

This document provides a comprehensive inventory of all files requiring Monix → Cats Effect 3 IO migration in Phase 6. Files are categorized by type and complexity to enable systematic migration.

### Migration Statistics

- **Total Remaining**: 92 files
- **Task occurrences**: 65
- **Observable occurrences**: 9
- **Scheduler occurrences**: 45

### Category Breakdown

1. **Integration Tests** (8 files) - 9%
2. **Unit Test Specs** (37 files) - 40%
3. **Test Utilities & Fixtures** (7 files) - 8%
4. **JSON-RPC Services** (22 files) - 24%
5. **Additional Main Components** (18 files) - 20%

---

## 1. Integration Tests (8 files)

**Priority**: High - These validate end-to-end functionality

### Database Integration Tests (1 file)

1. **src/it/scala/com/chipprbots/ethereum/db/RockDbIteratorSpec.scala**
   - Monix imports: 3
   - Complexity: Medium
   - Dependencies: Already partially migrated in Phase 1
   - Pattern: Observable iteration testing

### Ledger Integration Tests (1 file)

2. **src/it/scala/com/chipprbots/ethereum/ledger/BlockImporterItSpec.scala**
   - Complexity: High
   - Dependencies: BlockImporter (needs migration)
   - Pattern: Block import validation with Task

### Sync Integration Tests (6 files)

3. **src/it/scala/com/chipprbots/ethereum/sync/FastSyncItSpec.scala**
   - Complexity: Very High
   - Dependencies: Multiple sync components
   - Pattern: Fast sync end-to-end testing

4. **src/it/scala/com/chipprbots/ethereum/sync/RegularSyncItSpec.scala**
   - Complexity: Very High
   - Dependencies: Regular sync components
   - Pattern: Regular sync end-to-end testing

5. **src/it/scala/com/chipprbots/ethereum/sync/util/CommonFakePeer.scala**
   - Complexity: Medium
   - Pattern: Fake peer for testing
   - Dependencies: Network layer

6. **src/it/scala/com/chipprbots/ethereum/sync/util/FastSyncItSpecUtils.scala**
   - Complexity: Medium
   - Pattern: Fast sync test utilities

7. **src/it/scala/com/chipprbots/ethereum/sync/util/RegularSyncItSpecUtils.scala**
   - Complexity: Medium
   - Pattern: Regular sync test utilities

8. **src/it/scala/com/chipprbots/ethereum/sync/util/SyncCommonItSpecUtils.scala**
   - Complexity: Medium
   - Pattern: Common sync test utilities

---

## 2. Unit Test Specs (37 files)

**Priority**: High - Core functionality validation

### Blockchain Sync Test Specs (7 files)

9. **src/test/scala/com/chipprbots/ethereum/blockchain/sync/EtcPeerManagerFake.scala**
   - Monix imports: 5 (highest count)
   - Complexity: High
   - Pattern: Mock peer manager with Observable/Task
   - Critical for testing

10. **src/test/scala/com/chipprbots/ethereum/blockchain/sync/FastSyncSpec.scala**
    - Complexity: High
    - Pattern: Fast sync unit tests

11. **src/test/scala/com/chipprbots/ethereum/blockchain/sync/LoadableBloomFilterSpec.scala**
    - Complexity: Medium
    - Dependencies: LoadableBloomFilter (already migrated)
    - Pattern: Bloom filter loading tests

12. **src/test/scala/com/chipprbots/ethereum/blockchain/sync/ScenarioSetup.scala**
    - Complexity: Medium
    - Pattern: Test scenario setup utility

13. **src/test/scala/com/chipprbots/ethereum/blockchain/sync/fast/FastSyncBranchResolverActorSpec.scala**
    - Monix imports: 5 (highest count)
    - Complexity: Very High
    - Pattern: Actor-based fast sync testing

14. **src/test/scala/com/chipprbots/ethereum/blockchain/sync/regular/RegularSyncFixtures.scala**
    - Monix imports: 4
    - Complexity: Medium
    - Pattern: Test fixtures for regular sync

15. **src/test/scala/com/chipprbots/ethereum/blockchain/sync/regular/RegularSyncSpec.scala**
    - Complexity: High
    - Dependencies: Partially migrated in Phase 1
    - Pattern: Regular sync unit tests

### Consensus & Mining Test Specs (5 files)

16. **src/test/scala/com/chipprbots/ethereum/consensus/ConsensusImplSpec.scala**
    - Complexity: High
    - Dependencies: ConsensusImpl (already migrated)
    - Pattern: Consensus implementation tests

17. **src/test/scala/com/chipprbots/ethereum/consensus/blocks/BlockGeneratorSpec.scala**
    - Complexity: Medium
    - Pattern: Block generation tests

18. **src/test/scala/com/chipprbots/ethereum/consensus/pow/MinerSpecSetup.scala**
    - Complexity: Medium
    - Pattern: Miner test setup utility

19. **src/test/scala/com/chipprbots/ethereum/consensus/pow/PoWMiningCoordinatorSpec.scala**
    - Complexity: High
    - Dependencies: PoWMiningCoordinator (already migrated)
    - Pattern: Mining coordinator tests

20. **src/test/scala/com/chipprbots/ethereum/consensus/pow/miners/MockedMinerSpec.scala**
    - Complexity: Medium
    - Dependencies: MockedMiner (already migrated)
    - Pattern: Mocked miner tests

### Faucet Test Specs (3 files)

21. **src/test/scala/com/chipprbots/ethereum/faucet/FaucetHandlerSpec.scala**
    - Complexity: Medium
    - Dependencies: FaucetHandler (needs migration)
    - Pattern: Faucet handler tests

22. **src/test/scala/com/chipprbots/ethereum/faucet/jsonrpc/FaucetRpcServiceSpec.scala**
    - Complexity: Medium
    - Pattern: Faucet RPC service tests

23. **src/test/scala/com/chipprbots/ethereum/faucet/jsonrpc/WalletServiceSpec.scala**
    - Complexity: Medium
    - Dependencies: WalletService (needs migration)
    - Pattern: Wallet service tests

### ForkId Test Specs (1 file)

24. **src/test/scala/com/chipprbots/ethereum/forkid/ForkIdValidatorSpec.scala**
    - Complexity: Low
    - Dependencies: ForkIdValidator (needs migration)
    - Pattern: Fork ID validation tests

### JSON-RPC Test Specs (19 files)

25. **src/test/scala/com/chipprbots/ethereum/jsonrpc/CheckpointingJRCSpec.scala**
    - Complexity: Medium
    - Pattern: Checkpointing RPC tests

26. **src/test/scala/com/chipprbots/ethereum/jsonrpc/CheckpointingServiceSpec.scala**
    - Complexity: Medium
    - Dependencies: CheckpointingService (needs migration)

27. **src/test/scala/com/chipprbots/ethereum/jsonrpc/DebugServiceSpec.scala**
    - Complexity: Medium
    - Dependencies: DebugService (needs migration)

28. **src/test/scala/com/chipprbots/ethereum/jsonrpc/EthBlocksServiceSpec.scala**
    - Complexity: High
    - Dependencies: EthBlocksService (needs migration)

29. **src/test/scala/com/chipprbots/ethereum/jsonrpc/EthFilterServiceSpec.scala**
    - Complexity: Medium
    - Pattern: Filter service tests

30. **src/test/scala/com/chipprbots/ethereum/jsonrpc/EthInfoServiceSpec.scala**
    - Complexity: Medium
    - Dependencies: EthInfoService (needs migration)

31. **src/test/scala/com/chipprbots/ethereum/jsonrpc/EthMiningServiceSpec.scala**
    - Complexity: Medium
    - Dependencies: EthMiningService (needs migration)

32. **src/test/scala/com/chipprbots/ethereum/jsonrpc/EthProofServiceSpec.scala**
    - Complexity: Medium
    - Dependencies: EthProofService (needs migration)

33. **src/test/scala/com/chipprbots/ethereum/jsonrpc/EthTxServiceSpec.scala**
    - Complexity: High
    - Dependencies: EthTxService (needs migration)

34. **src/test/scala/com/chipprbots/ethereum/jsonrpc/EthUserServiceSpec.scala**
    - Complexity: Medium
    - Dependencies: EthUserService (needs migration)

35. **src/test/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcControllerEthLegacyTransactionSpec.scala**
    - Monix imports: 2
    - Complexity: High
    - Dependencies: JsonRpcController (needs migration)

36. **src/test/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcControllerEthSpec.scala**
    - Monix imports: 2
    - Complexity: High
    - Dependencies: JsonRpcController (needs migration)

37. **src/test/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcControllerPersonalSpec.scala**
    - Monix imports: 2
    - Complexity: High
    - Dependencies: JsonRpcController (needs migration)

38. **src/test/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcControllerSpec.scala**
    - Monix imports: 2
    - Complexity: High
    - Dependencies: JsonRpcController (needs migration)

39. **src/test/scala/com/chipprbots/ethereum/jsonrpc/MantisJRCSpec.scala**
    - Complexity: Medium
    - Pattern: Mantis RPC tests

40. **src/test/scala/com/chipprbots/ethereum/jsonrpc/MantisServiceSpec.scala**
    - Complexity: Medium
    - Dependencies: MantisService (needs migration)

41. **src/test/scala/com/chipprbots/ethereum/jsonrpc/NetServiceSpec.scala**
    - Complexity: Low
    - Dependencies: NetService (needs migration)

42. **src/test/scala/com/chipprbots/ethereum/jsonrpc/PersonalServiceSpec.scala**
    - Complexity: Medium
    - Dependencies: PersonalService (needs migration)

43. **src/test/scala/com/chipprbots/ethereum/jsonrpc/QAServiceSpec.scala**
    - Complexity: Medium
    - Dependencies: QAService (needs migration)

44. **src/test/scala/com/chipprbots/ethereum/jsonrpc/QaJRCSpec.scala**
    - Monix imports: 2
    - Complexity: Medium
    - Pattern: QA RPC tests

45. **src/test/scala/com/chipprbots/ethereum/jsonrpc/server/http/JsonRpcHttpServerSpec.scala**
    - Complexity: Medium
    - Dependencies: JsonRpcHttpServer (needs migration)

### Other Test Specs (2 files)

46. **src/test/scala/com/chipprbots/ethereum/ledger/LedgerTestSetup.scala**
    - Complexity: Medium
    - Pattern: Ledger test setup utility

47. **src/test/scala/com/chipprbots/ethereum/network/discovery/PeerDiscoveryManagerSpec.scala**
    - Monix imports: 3
    - Complexity: High
    - Dependencies: PeerDiscoveryManager (already migrated)
    - Pattern: Peer discovery tests

### Transactions Test Specs (1 file)

48. **src/test/scala/com/chipprbots/ethereum/transactions/LegacyTransactionHistoryServiceSpec.scala**
    - Complexity: High
    - Dependencies: TransactionHistoryService (already migrated)
    - Pattern: Transaction history tests

---

## 3. Test Utilities & Fixtures (1 file - already covered above)

49. **src/test/scala/com/chipprbots/ethereum/jsonrpc/ProofServiceDummy.scala**
    - Complexity: Low
    - Pattern: Dummy proof service for testing

---

## 4. JSON-RPC Services (22 files)

**Priority**: Medium - Application layer services

### Core JSON-RPC Services (16 files)

50. **src/main/scala/com/chipprbots/ethereum/jsonrpc/CheckpointingService.scala**
    - Complexity: Medium
    - Pattern: Task-based checkpointing

51. **src/main/scala/com/chipprbots/ethereum/jsonrpc/DebugService.scala**
    - Complexity: Medium
    - Pattern: Debug RPC endpoints

52. **src/main/scala/com/chipprbots/ethereum/jsonrpc/EthBlocksService.scala**
    - Complexity: High
    - Pattern: Block query endpoints with Task

53. **src/main/scala/com/chipprbots/ethereum/jsonrpc/EthInfoService.scala**
    - Complexity: Medium
    - Pattern: Ethereum info endpoints

54. **src/main/scala/com/chipprbots/ethereum/jsonrpc/EthMiningService.scala**
    - Complexity: Medium
    - Pattern: Mining RPC endpoints

55. **src/main/scala/com/chipprbots/ethereum/jsonrpc/EthProofService.scala**
    - Complexity: Medium
    - Pattern: Proof generation endpoints

56. **src/main/scala/com/chipprbots/ethereum/jsonrpc/EthTxService.scala**
    - Complexity: High
    - Pattern: Transaction submission/query with Task

57. **src/main/scala/com/chipprbots/ethereum/jsonrpc/EthUserService.scala**
    - Complexity: Medium
    - Pattern: User account endpoints

58. **src/main/scala/com/chipprbots/ethereum/jsonrpc/FilterManager.scala**
    - Complexity: Medium
    - Pattern: Event filter management

59. **src/main/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcController.scala**
    - Complexity: High
    - Pattern: Main RPC controller with routing

60. **src/main/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcHealthChecker.scala**
    - Complexity: Low
    - Pattern: Health checking

61. **src/main/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcHealthcheck.scala**
    - Complexity: Low
    - Pattern: Health check trait

62. **src/main/scala/com/chipprbots/ethereum/jsonrpc/MantisService.scala**
    - Complexity: Medium
    - Pattern: Mantis-specific RPC endpoints

63. **src/main/scala/com/chipprbots/ethereum/jsonrpc/NetService.scala**
    - Complexity: Low
    - Pattern: Network info endpoints

64. **src/main/scala/com/chipprbots/ethereum/jsonrpc/NodeJsonRpcHealthChecker.scala**
    - Complexity: Low
    - Pattern: Node health checker

65. **src/main/scala/com/chipprbots/ethereum/jsonrpc/PersonalService.scala**
    - Complexity: Medium
    - Pattern: Personal account management

66. **src/main/scala/com/chipprbots/ethereum/jsonrpc/QAService.scala**
    - Complexity: Low
    - Pattern: QA/testing endpoints

67. **src/main/scala/com/chipprbots/ethereum/jsonrpc/TestService.scala**
    - Complexity: Low
    - Pattern: Test mode endpoints

68. **src/main/scala/com/chipprbots/ethereum/jsonrpc/Web3Service.scala**
    - Complexity: Low
    - Pattern: Web3 standard endpoints

### JSON-RPC Infrastructure (4 files)

69. **src/main/scala/com/chipprbots/ethereum/jsonrpc/client/RpcClient.scala**
    - Complexity: Medium
    - Pattern: RPC client with Task-based calls

70. **src/main/scala/com/chipprbots/ethereum/jsonrpc/package.scala**
    - Complexity: Low
    - Pattern: Package-level utilities

71. **src/main/scala/com/chipprbots/ethereum/jsonrpc/server/controllers/JsonRpcBaseController.scala**
    - Complexity: Medium
    - Pattern: Base controller with Task handling

72. **src/main/scala/com/chipprbots/ethereum/jsonrpc/server/http/JsonRpcHttpServer.scala**
    - Complexity: Medium
    - Pattern: HTTP server with Task-based routing

73. **src/main/scala/com/chipprbots/ethereum/jsonrpc/server/ipc/JsonRpcIpcServer.scala**
    - Complexity: Medium
    - Pattern: IPC server with Task-based communication

### Faucet JSON-RPC (5 files)

74. **src/main/scala/com/chipprbots/ethereum/faucet/FaucetHandler.scala**
    - Complexity: Medium
    - Pattern: Faucet request handling with Task

75. **src/main/scala/com/chipprbots/ethereum/faucet/jsonrpc/FaucetHandlerSelector.scala**
    - Complexity: Low
    - Pattern: Handler selection logic

76. **src/main/scala/com/chipprbots/ethereum/faucet/jsonrpc/FaucetJsonRpcController.scala**
    - Complexity: Medium
    - Pattern: Faucet RPC controller

77. **src/main/scala/com/chipprbots/ethereum/faucet/jsonrpc/FaucetJsonRpcHealthCheck.scala**
    - Complexity: Low
    - Pattern: Faucet health check

78. **src/main/scala/com/chipprbots/ethereum/faucet/jsonrpc/WalletRpcClient.scala**
    - Complexity: Low
    - Pattern: Wallet RPC client

79. **src/main/scala/com/chipprbots/ethereum/faucet/jsonrpc/WalletService.scala**
    - Complexity: Medium
    - Pattern: Wallet service with Task

---

## 5. Additional Main Components (18 files)

**Priority**: Medium to High - Core functionality

### Blockchain Sync Components (3 files)

80. **src/main/scala/com/chipprbots/ethereum/blockchain/sync/regular/BlockFetcher.scala**
    - Complexity: High
    - Pattern: Block fetching with Task/Observable
    - Dependencies: Network layer

81. **src/main/scala/com/chipprbots/ethereum/blockchain/sync/regular/BlockImporter.scala**
    - Complexity: Very High
    - Pattern: Block import with complex Task orchestration
    - Critical component

82. **src/main/scala/com/chipprbots/ethereum/blockchain/sync/regular/StateNodeFetcher.scala**
    - Complexity: High
    - Pattern: State node fetching with Task

### Database Storage (3 files)

83. **src/main/scala/com/chipprbots/ethereum/db/storage/EvmCodeStorage.scala**
    - Complexity: Low
    - Dependencies: Extends KeyValueStorage (already migrated)
    - Pattern: EVM code storage

84. **src/main/scala/com/chipprbots/ethereum/db/storage/NodeStorage.scala**
    - Complexity: Low
    - Dependencies: Extends KeyValueStorage (already migrated)
    - Pattern: Node storage

85. **src/main/scala/com/chipprbots/ethereum/db/storage/TransactionalKeyValueStorage.scala**
    - Complexity: Medium
    - Pattern: Transactional storage wrapper

### Domain (1 file)

86. **src/main/scala/com/chipprbots/ethereum/domain/SignedTransaction.scala**
    - Complexity: Low
    - Pattern: Transaction domain model with minimal Monix

### ForkId (1 file)

87. **src/main/scala/com/chipprbots/ethereum/forkid/ForkIdValidator.scala**
    - Complexity: Low
    - Pattern: Fork ID validation

### Node Builder (2 files)

88. **src/main/scala/com/chipprbots/ethereum/nodebuilder/NodeBuilder.scala**
    - Complexity: High
    - Pattern: Node construction with Scheduler/Task
    - Critical infrastructure

89. **src/main/scala/com/chipprbots/ethereum/nodebuilder/TestNode.scala**
    - Complexity: Medium
    - Pattern: Test node setup

### Test Mode (2 files)

90. **src/main/scala/com/chipprbots/ethereum/testmode/TestModeComponentsProvider.scala**
    - Complexity: Medium
    - Pattern: Test mode component provider

91. **src/main/scala/com/chipprbots/ethereum/testmode/TestmodeMining.scala**
    - Complexity: Low
    - Pattern: Test mode mining

### Transactions (1 file)

92. **src/main/scala/com/chipprbots/ethereum/transactions/TransactionPicker.scala**
    - Complexity: Medium
    - Pattern: Transaction selection with Task

---

## Migration Strategy

### Phase 6.1: High-Priority Services & Components (20 files)

**Focus**: Core services required for basic functionality

1. JSON-RPC Core Services (16 files):
   - JsonRpcController
   - EthBlocksService
   - EthTxService
   - JsonRpcBaseController
   - JsonRpcHttpServer
   - FilterManager
   - CheckpointingService
   - DebugService
   - EthInfoService
   - EthMiningService
   - EthProofService
   - EthUserService
   - MantisService
   - NetService
   - PersonalService
   - Web3Service

2. Critical Blockchain Components (4 files):
   - BlockImporter
   - BlockFetcher
   - StateNodeFetcher
   - NodeBuilder

**Estimated effort**: 3-5 days

### Phase 6.2: Test Fixtures & Utilities (15 files)

**Focus**: Test infrastructure to enable test migration

1. Test Utilities (8 files):
   - EtcPeerManagerFake
   - RegularSyncFixtures
   - ScenarioSetup
   - LedgerTestSetup
   - MinerSpecSetup
   - CommonFakePeer
   - FastSyncItSpecUtils
   - RegularSyncItSpecUtils
   - SyncCommonItSpecUtils

2. Supporting Services (7 files):
   - FaucetHandler
   - WalletService
   - ForkIdValidator
   - TransactionPicker
   - Faucet JSON-RPC components (4 files)

**Estimated effort**: 2-3 days

### Phase 6.3: Integration Tests (8 files)

**Focus**: End-to-end validation

1. Sync Integration Tests (6 files)
2. Ledger Integration Tests (1 file)
3. Database Integration Tests (1 file)

**Estimated effort**: 3-4 days

### Phase 6.4: Unit Test Specs (37 files)

**Focus**: Comprehensive test coverage

1. Blockchain Sync Specs (7 files)
2. Consensus & Mining Specs (5 files)
3. JSON-RPC Specs (19 files)
4. Faucet Specs (3 files)
5. Other Specs (3 files)

**Estimated effort**: 5-7 days

### Phase 6.5: Remaining Components (12 files)

**Focus**: Final cleanup

1. Storage Components (3 files)
2. JSON-RPC Infrastructure (4 files)
3. Test Mode (2 files)
4. Domain (1 file)
5. Node Builder (2 files)

**Estimated effort**: 2-3 days

---

## Total Estimated Effort

**Phase 6 Total**: 15-22 days (3-4 weeks) for 92 files

**Breakdown**:
- Phase 6.1: 3-5 days (20 files)
- Phase 6.2: 2-3 days (15 files)
- Phase 6.3: 3-4 days (8 files)
- Phase 6.4: 5-7 days (37 files)
- Phase 6.5: 2-3 days (12 files)

---

## Risk Factors

### High Complexity Files (Require Extra Care)

1. **BlockImporter** - Complex block import orchestration
2. **FastSyncBranchResolverActorSpec** - 5 Monix imports, complex actor testing
3. **EtcPeerManagerFake** - 5 Monix imports, critical for testing
4. **JsonRpcController** - Main RPC routing with many dependencies
5. **FastSyncItSpec** - End-to-end fast sync testing
6. **RegularSyncItSpec** - End-to-end regular sync testing

### Dependencies

Many test files depend on main components being migrated first:
- Test specs depend on their corresponding service migrations
- Integration tests depend on multiple component migrations
- Fixtures depend on the components they're testing

**Recommendation**: Follow the phased approach above to minimize dependency issues.

---

## Success Criteria

- [ ] All 92 files migrated to IO/Stream
- [ ] All compilation errors resolved
- [ ] All tests passing
- [ ] Performance within 10% of baseline
- [ ] No Monix imports remaining
- [ ] Documentation updated
- [ ] Code review completed

---

## Next Steps

1. **Review & Approve**: Team review of this inventory
2. **Begin Phase 6.1**: Start with high-priority services
3. **Track Progress**: Update this document as files are migrated
4. **Validate**: Run tests after each sub-phase
5. **Document**: Update action plan with completion status

**Ready to begin systematic Phase 6 migration.**
