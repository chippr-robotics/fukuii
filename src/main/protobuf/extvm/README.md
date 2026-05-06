# fukuii-extvm-pb

Protobuf API for integrating Fukuii with an external VM process.

 * **msg.proto** — message definitions for the external VM protocol
 * **VERSION** — protocol version included in the `Hello` message

External VM support (IELE, KEVM) was experimental in the original Mantis codebase and has been removed.
Only `vm.mode = "internal"` is supported; the internal EVM is always used.
