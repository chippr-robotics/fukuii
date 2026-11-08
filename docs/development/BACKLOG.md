# Development Backlog

This list moved to the shared backlog, which the [Tracker](tracker/index.md) page shows (constitution Principle
VIII). Its items were migrated on 2026-11-08 at their stated priorities (Medium → P2, Low → P3), each with its old id
in `refs`:

| Old id | Shared backlog | Old id | Shared backlog |
| --- | --- | --- | --- |
| P-001 | B-103 | N-002 | B-111 |
| P-002 | B-104 | N-003 | B-112 |
| P-003 | B-105 | N-004 | B-113 |
| P-004 | B-106 | N-005 | B-114 |
| A-001 | B-107 | R-001 | B-115 |
| A-002 | B-108 | T-001 | B-116 |
| A-003 | B-109 | T-002 | B-117 |
| A-004 | B-110 | N-001 | not migrated |

N-001 (a SNAP server) is done: `network/snapserver/SnapServer.scala` serves the four SNAP requests. A-003, A-004 and
T-002 name `SyncStateSchedulerActor.scala`, and A-001 names `FastSync.scala`; neither file exists any more, so those
items carry a note to re-scope or drop them.
