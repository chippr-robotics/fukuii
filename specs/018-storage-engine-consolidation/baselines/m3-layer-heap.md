# M3: what a forward layer holds on the heap, against what the overlay counts (T134)

**Status: measured in the small, on synthetic blocks.** The overlay's bounds (`state.overlay.*`, data-model 6.4) are bounds on
`ForwardLayer.bytes`: for each position a block wrote, the row key, the node it left, and 440 bytes for everything beside the
node (the key's objects, the hash of the prior, the entry in the map by position and, once a read has asked for a node by its
hash, the entry in that map). M3 asks whether that count is the heap's. It is, from above: **in every shape measured the count
is no less than the heap, by 9 % to 2.2 times.** So a bound on the count is a bound on the heap, and the defaults stay as they
are. What M3 was also to do, set the defaults from M1's bytes per block on real chains, waits for M1 and M2.

## How it was measured

`src/benchmark/scala/com/chipprbots/ethereum/db/storage/pathstate/LayerHeapBenchmark.scala`, added with this note:

```
sbt "Benchmark / testOnly *LayerHeapBenchmark"
```

- Sibling blocks of one shape are executed on one datadir and kept as layers. The heap in use is read after a collection, the
  layers are dropped, and it is read again: the difference is what the layers held. The hash store that says what each state
  is holds every block's nodes too, and is the same at both readings.
- **Not JOL**, which the task names: it is not a dependency of the build, and a heap difference over hundreds of layers
  answers the same question to within a few per cent. The figures move by that much from run to run.
- JDK 21.0.12, Linux x86_64, 4 cores, G1, compressed pointers (a 3.4 GiB heap).

## Result

| Shape of a block | Layers | Positions | Node bytes | Counted | Heap | Heap / counted | Heap beside the node, per position |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| one account | 2,000 | 6,000 | 1,447,618 | 4,095,618 | 3,282,776 | 0.80 | 306 |
| 50 slots of a trie of 40 | 400 | 34,686 | 2,732,933 | 19,129,009 | 9,966,440 | 0.52 | 209 |
| 1,000 slots of a new trie | 40 | 54,592 | 3,502,917 | 29,384,034 | 13,473,280 | 0.46 | 183 |
| a trie of 40 replaced, 5 slots | 400 | 3,836 | 467,776 | 2,420,675 | 1,370,016 | 0.57 | 235 |
| one account, by hash built | 2,000 | 6,000 | 1,447,618 | 4,095,618 | 3,722,312 | 0.91 | 379 |
| 50 slots, by hash built | 400 | 34,686 | 2,732,933 | 19,129,009 | 13,736,576 | 0.72 | 317 |
| 1,000 slots, by hash built | 40 | 54,592 | 3,502,917 | 29,384,034 | 19,193,952 | 0.65 | 287 |
| replaced trie, by hash built | 400 | 3,836 | 467,776 | 2,420,675 | 1,733,880 | 0.72 | 330 |

## What it says

- **The count is safe.** The most a layer held beside its nodes was 379 bytes per position (a block of three positions, with
  its map by hash built: the layer's own fixed cost is spread over few entries). The count allows 440 and the row key.
- **It is generous for large blocks**: a block of a thousand slots is counted at about twice what it holds. A bound of 128 MiB
  on the count is then some 60 to 90 MiB of heap. That is the side to err on, and the 440 is not lowered on this evidence: a
  layer of a real block has longer nodes (more branches of sixteen) and the same overhead, so its ratio is nearer to 1.
- **A storage trie that is replaced costs a layer nothing** (part 1): the fourth shape holds five slots' worth, not the forty
  rows that went.
- The measurement to make again with real blocks is the one M1 feeds: bytes per block at Sepolia's and Platåberget's gas
  limits, against `max-layer-bytes` (64 MiB) and what a branch of them holds against `pinned-max-bytes`.
