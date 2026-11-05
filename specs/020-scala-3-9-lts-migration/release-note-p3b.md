# Release-note line for PR 7: published modules' API shape (spec 020 PR 5b, P3b)

Draft text. PR 7 folds it into the release-note draft.

> **Source-level API change in the published modules (bytes, rlp, crypto, scalanet).** Public `implicit class`
> wrappers were replaced by `extension` methods with the same method names. Call syntax (`bs.toHex`,
> `codec.xmap(f, g)`, `a + b`) and wildcard imports (`import X.*`) are unchanged. Code that imports or names a removed
> wrapper class itself (`import ByteStringUtils.ByteStringOps`, `ByteStringOps(bs)`) must import the extension method
> instead (`import ByteStringUtils.toHex`) or use the wildcard import. Compiled consumers must be rebuilt: the wrapper
> classes (`ByteStringUtils$ByteStringOps` and the others below) no longer exist, and the extension methods compile to
> `Owner$.method(receiver, ...)`.

| Module | Removed public class | Replacement extension (same object) |
|--------|----------------------|--------------------------------------|
| bytes | `ByteStringUtils.Padding` | `extension (bs: ByteString)`: `padToByteString(length, b)` |
| bytes | `ByteStringUtils.ByteStringOps` | `extension (bytes: ByteString)`: `toHex` |
| rlp | `RLPCodec.Ops[A]` | `extension [A](codec: RLPCodec[A])`: `xmap[B](f, g)` |
| crypto | `FiniteField.Ops.FiniteFieldOps[T]` | `extension [T](t: T)(using F: FiniteField[T])`: `+`, `*`, `-`, `doubled()`, `squared()`, `inversed()`, `negated()`, `isValid()`, `isZero()` |
| scalanet | `peergroup.implicits.NextOps[A]` | `extension [A](next: IO[Option[A]])`: `toStream`, `withCancelToken` |
| scalanet | `peergroup.implicits.PeerGroupOps[A, M]` | `extension [A, M](group: PeerGroup[A, M])`: `serverEventStream` |
| scalanet | `peergroup.implicits.ChannelOps[A, M]` | `extension [A, M](channel: Channel[A, M])`: `channelEventStream` |
| scalanet | `PeerGroup.ServerEvent.ServerOps[A, M]` | `extension [A, M](stream: Stream[IO, ServerEvent[A, M]])`: `collectChannelCreated`, `collectHandshakeFailure` (found through `ServerEvent`'s companion, no import) |

Not changed: the conversion wrappers (`ByteStringUtils.ByteString{Self,Array,Byte}Element`, and in the node module the
`XEnc` message and consensus encoders) stay `implicit class` (user decision 2026-11-04; see `scala3-style.md` S4).
scalanet's unused `BufferConversionOps` is untouched.

Also converted, but not visible downstream so not listed above: `DynamicTLSPeerGroupInternals.ChannelOps` and `DynamicTLSExtension.ExtensionPublicKey.ExtensionPublicKeyOps` sit in objects that are `private[peergroup]` / `private[scalanet]`, and the two scalanetDiscovery sites (`DiscoveryNetwork.ChannelOps`, `Discv5SyncResponder.IterableHasAsScala`) were `private`. scalanetDiscovery's public API is unchanged.
