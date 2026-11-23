# OPS-004: Operator Actions in the Terminal Dashboard

**Status**: Accepted

**Date**: November 2026

**Deciders**: Chippr Robotics LLC Engineering Team

## Context

The `--tui` dashboard (OPS-001, specs 027 and 029) showed a node and could do one thing to it: stop it. Spec 029
slice 7 lets an operator act from it: change a log level, drop or block a peer, stop the miner, stop and start a
JSON-RPC server, check the database, write a support bundle.

A dashboard that can act on a node is a second control surface beside the admin RPC namespace. Two control surfaces
drift apart unless something holds them together, and a key pressed in a terminal is easier to press by accident
than an RPC call is to send by accident. Three questions had to be settled before any action was written:

1. How much confirmation does each action need, and who decides?
2. Where does the code that carries an action out live?
3. What does adding these actions expose that was not exposed before?

## Decision

### Every action has a risk class, and the class sets its confirmation

| Class | What it means | Confirmation |
| --- | --- | --- |
| Observe | Reads; changes nothing | None |
| Adjust | Changes a setting that the opposite action puts back | A dialog with Cancel focused; `Tab` to Apply, then `Enter` |
| Disrupt | Interrupts something or someone: a peer, a server's callers | A dialog answered with `y` |
| Halt | Ends something that the process cannot start again | The word `stop`, typed, then `Enter` |

The class is a field of the action's entry in one registry (`console/ops/Actions.scala`), and the dialog is chosen
from the class alone. An action cannot ask for a lighter dialog than its class has. Text pasted into the terminal
confirms nothing, in any class.

The class follows what the node can undo, not what the action is called. `mining stop` is a Halt: the miner's
coordinator ends when told to stop and nothing in the process makes another, so the only undo is a restart.

### Every dialog shows a preview built from live state

Before an action above Observe can be confirmed, its handler reads the node and says what will change: the peer by
id, client and address; the connections that will drop; the level a logger has now and will have. A handler that
finds the action cannot be done refuses in the preview, with the reason, and a refused preview cannot be confirmed.
If the preview changes while the dialog is open, the dialog starts again from Cancel.

### One implementation per action, called in process

Where an admin RPC method already does what an action does, the action calls the same service function the method
calls (`AdminService.blockIP`, `EthMiningService.stopMiner`, and so on). The dashboard has no copy of that logic and
does not go through the RPC server: it works with every namespace disabled and every transport off.

Where the node had no such function, this spec adds one beside the code it concerns (`ServerLifecycle` for the
HTTP and WebSocket servers, `StorageConsistencyChecker.scan` for the database check), and it is written so the
start-up path and the action share it.

The handlers in `console/ops/` take functions, not services. `nodebuilder/TuiActions.scala` is the one place that
ties each handler to the node. That keeps `console/` free of the node's actors and lets each handler be tested with
plain functions.

### Nothing new is reachable from the network

No RPC method is added, no namespace is enabled, and no listener is opened. The actions can be run only by someone
at the terminal the node was started in, who could already stop the process.

### Every attempt is logged

Each attempt writes one INFO line from the logger `fukuii.tui.ops`: the action, its parameters, and whether it was
confirmed, cancelled, refused or failed, with the reason. No action takes a secret as a parameter, so no audit line
holds one.

### A switch turns every action off

With `fukuii.tui.read-only = true` (or `FUKUII_TUI_READ_ONLY` set) only Observe actions run. The check is made in
the runner, so no handler can be reached around it. `Ctrl-C` still stops the node: it is a signal to the process,
not an action of the dashboard.

### The dashboard says what it does not offer

The Ops view lists what an operator may look for and not find (restarting the node, reloading configuration) with the reason for each.

## Consequences

**Good**

- An admin RPC method and its dashboard action cannot disagree: they are one function. Making `peer block` work
  meant fixing `admin_blockIP`, which had recorded an address and blocked nothing (B-277).
- The confirmation an action needs is read off one table, and a test fails when the table and the contract
  (`specs/029-tui-command-center/contracts/actions.md`) differ.
- A node run with `read-only` set has a dashboard that can be handed to someone who should watch and not touch.

**Costs**

- An action is only as good as the service function behind it. Where that function is weak the action says so
  instead of covering for it: `mining start` is always refused, because `miner_start` cannot start a miner that was
  stopped (B-278, B-279).
- Compaction is offered as `compact`, a Disrupt that asks for confirmation (B-281, B-371). The data source no longer
  holds its lock across a compaction, and `compactAll` takes the column families one at a time, the largest first, so
  closing the node cancels it between or inside families. Only one runs at a time.
- The preview reads the node when the dialog opens, on a worker thread. An action on a node that is slow to answer
  shows "reading" until it does, and cannot be confirmed before then.

## Alternatives Considered

- **Call the admin RPC over the node's own HTTP listener.** One implementation for free, but the actions would work
  only with the admin namespace enabled on a transport, which is the configuration operators are told to avoid.
- **One confirmation for everything (`y`).** Simpler, and it makes stopping a validator's execution client as easy
  to confirm as changing a log level.
- **Let each action choose its own dialog.** Flexible, and nothing would stop a destructive action from choosing
  the lightest one.

## References

- `specs/029-tui-command-center/spec.md` FR-047 to FR-058
- `specs/029-tui-command-center/contracts/actions.md`
- [OPS-001: Enhanced Console User Interface](OPS-001-console-ui.md)
