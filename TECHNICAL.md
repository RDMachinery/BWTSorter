# BWTSorter — Technical Notes

This document describes how BWTSorter works internally: the compression pipeline, the algorithms behind each stage, the on-disk format, the design trade-offs, and how it compares with bzip2 and xz. It is aimed at developers and the curious. For usage instructions, see [README.md](README.md).

BWTSorter is a single-file Java program (`BWTSorter.java`, about 710 lines, no dependencies beyond the JDK). It is a block-sorting compressor, related to `bzip2` but with a modern back end: a linear-time suffix sort, large blocks, and a context-mixing model driving an arithmetic coder. This places it in the same family as `bsc` and `bcm`.

---

## Contents

1. [Pipeline overview](#1-pipeline-overview)
2. [Stage 1: Burrows–Wheeler Transform](#2-stage-1-burrowswheeler-transform)
3. [Stage 2: Context-mixing model](#3-stage-2-context-mixing-model)
4. [Stage 3: Binary arithmetic coder](#4-stage-3-binary-arithmetic-coder)
5. [Stored-block fallback](#5-stored-block-fallback)
6. [Decompression and the inverse BWT](#6-decompression-and-the-inverse-bwt)
7. [File format](#7-file-format)
8. [Integrity and error handling](#8-integrity-and-error-handling)
9. [Complexity and memory](#9-complexity-and-memory)
10. [Benchmarks](#10-benchmarks)
11. [Why it beats xz on some files and not others](#11-why-it-beats-xz-on-some-files-and-not-others)
12. [How the model was tuned](#12-how-the-model-was-tuned)
13. [Version history](#13-version-history)
14. [Limitations and possible extensions](#14-limitations-and-possible-extensions)
15. [Code map](#15-code-map)

---

## 1. Pipeline overview

Input is split into blocks, **32 MB by default** (configurable from 1 to 256 MB), and each block is compressed independently.

```
           ┌─────────────────┐   ┌──────────────────────┐   ┌────────────┐
 block ──▶ │ BWT via SA-IS   │──▶│ context-mixing model │──▶│ arithmetic │──▶ payload
 (bytes)   │ (suffix sort)   │   │ (predicts each bit)  │──▶│   coder    │
           └─────────────────┘   └──────────────────────┘   └────────────┘
                   │                        ▲  bit                │
             primary index                  └─────────────────────┘
                                                  if payload ≥ block size
                                                    → store block raw
```

The BWT is a *permutation*: it makes the data no smaller, only far more predictable. The model then estimates, for every bit of the BWT output, the probability that it is a 1. The arithmetic coder spends almost exactly −log₂(p) bits on each bit it codes, so a better model translates directly into a smaller file.

Decompression runs the identical model in lock-step with an arithmetic decoder to recover the BWT output, then inverts the BWT.

---

## 2. Stage 1: Burrows–Wheeler Transform

### What it computes

Take the block `s` (length `n`) and append a virtual sentinel `$` that sorts before every byte. Sort all rotations of `s$` into a conceptual matrix. The BWT is the **last column** `L` of that matrix.

Example for `banana`:

```
 row  rotation    L
  0   $banana  →  a
  1   a$banan  →  n
  2   ana$ban  →  n
  3   anana$b  →  b
  4   banana$  →  $   ← primary index = 4
  5   na$bana  →  a
  6   nana$ba  →  a
```

`L = "annb$aa"`. The `$` carries no information except its position, so BWTSorter stores `L` without it (`"annbaa"`, exactly `n` bytes) together with the **primary index** 4.

Each row is sorted by the text that *follows* its last character, so `L[i]` is the character that *precedes* the context at row `i`. In real data a given context is preceded by a small set of characters ("he " is very often preceded by "t"). Rows sharing a context are adjacent, so `L` consists of long stretches dominated by one or two symbols.

### Why a sentinel

Because `$` is unique and smallest, sorting the rotations of `s$` is the same as sorting the **suffixes** of `s$`. That turns the problem into suffix-array construction, which has fast, well-understood linear-time algorithms. It also removes the special case of periodic input (e.g. `abcabc…`), where plain rotations can be identical.

### SA-IS: linear-time suffix sorting

The suffix array is built with **SA-IS** (Nong, Zhang and Chan, 2009). The implementation follows the structure of the AtCoder Library.

1. **Classify** each suffix as *S-type* (smaller than the suffix after it) or *L-type* (larger). This takes one right-to-left scan.
2. **LMS positions.** Find the *left-most S* positions: S-type positions with an L-type position before them. They split the text into LMS substrings.
3. **Induced sorting.** Drop the LMS suffixes into the ends of their first-character buckets. One left-to-right scan places every L-type suffix in order, and one right-to-left scan places every S-type suffix. This sorts the LMS *substrings* correctly.
4. **Recursion.** Name each LMS substring by its rank. If all names are distinct, the LMS suffix order is known. Otherwise, solve the suffix array of the name string recursively; it is at most half as long.
5. **Final induce.** Place the LMS suffixes in their now-correct order and induce once more. This yields the full suffix array.

Every step is linear, and each recursion level at least halves the input, so the total is **O(n)** regardless of content.

The implementation was checked against a brute-force sort on 20,000 random strings, including binary alphabets, where runs and repeats are most likely to trip up induced sorting.

### Reading off the BWT

With suffix array `SA` of `s` (the sentinel suffix is implicit as row 0):

```
L[0] = s[n−1]                     // row 0 is the suffix "$", preceded by s[n−1]
for each SA[i]:
    if SA[i] == 0: primary = i + 1   // the whole block, preceded by '$' (omitted)
    else:          append s[SA[i] − 1]
```

### Why large blocks matter

The previous, bzip2-style version used 900 KB blocks. Repeated material further apart than that, such as a file containing several versions of the same document or a mailbox quoting earlier messages, could not be seen as repeated. With 32 MB blocks, the suffix sort places every copy of a repeated passage next to every other copy, and the model codes the repeats for almost nothing. This was the biggest single source of improvement on large, redundant inputs.

---

## 3. Stage 2: Context-mixing model

Rather than transforming the BWT output further (the classic Move-To-Front + Huffman approach), BWTSorter predicts it directly, **one bit at a time**, most-significant bit first. For each bit the model outputs a 12-bit probability that the bit is 1.

### Building blocks

**Stretch and squash.** Probabilities are combined in the *logistic* domain: `stretch(p) = ln(p / (1 − p))` and its inverse `squash`. Both are integer lookup tables (±2047 ↔ 0…4095), so the encoder and decoder compute bit-identical predictions on any machine.

**StateMap.** This is an adaptive probability per context, stored as a 22-bit probability plus a 10-bit hit count in one `int`. After each bit, the probability moves toward the observed bit by `1 / (count + 1.5)`. New contexts therefore learn very fast (the first observation moves them two-thirds of the way). The count stops at a per-model **limit**, which sets how quickly a settled context can still adapt.

**Mixer.** This is a gated linear mixer. It computes a weighted sum of the stretched input probabilities, squashes it back to a probability, and after each bit nudges every weight by `input × error × learning rate` (online gradient descent on coding cost). The set of weights used is selected by a small context, so the mixer can learn, for example, that the run model is trustworthy after a long run but not after a short one.

**APM / SSE.** An *adaptive probability map* takes an already-mixed probability and a context, and maps it through a learned curve (33 interpolation points along the stretch axis). It corrects systematic over- or under-confidence that the mixer can't express.

### The inputs

| Input | Context | Limit | What it captures |
|---|---|---|---|
| order 0 | bits of current byte so far | 15 | overall symbol frequencies in the current region |
| order 1 | previous byte + partial byte | 12 | "after an `e` comes…" within the current run of contexts |
| order 2 | two previous bytes + partial byte | 8 | longer local patterns, such as alternating pairs |
| run | previous byte, run-length bucket, partial byte | 127 | how likely a run of this symbol is to continue at this length |
| run match | run-length bucket, the bit the previous byte predicts, partial byte (while still matching) | 255 | "does this byte repeat the last one?" as a dedicated question |
| bias | constant | — | lets the mixer shift the overall estimate |

Run length is bucketed into 16 levels: 0–7 exactly, then 8–15 in pairs, 16–63 in steps of 16, and 64+.

The mixer's weight set is selected by **(run bucket, partial byte)**, which gives 4,096 weight sets of 6 weights. Its output then goes through two APMs:

- `apm1`, keyed on order-1 context (previous byte + partial byte);
- `apm2`, keyed on (run bucket, partial byte).

The final probability is `(mixer + apm1 + 2 × apm2) / 4`, clamped to 1…4095.

### Why these contexts

BWT output has a very particular structure. It is a sequence of **regions**, one per sorted context, each a few to a few thousand bytes long. Within a region, one or two symbols dominate. Between regions, the statistics change abruptly. That has two consequences:

- **Runs are the main signal.** The single best predictor of the next byte is "the same as the last one", with a confidence that depends on how long the run has been going. Two of the five models are devoted to this.
- **Fast forgetting wins.** An order-1 model that remembers what followed `e` across the whole block is mostly wrong, because each region has its own answer. The tuned limits (8–15 for the order-n models) make these models track only the last few occurrences.

---

## 4. Stage 3: Binary arithmetic coder

The coder keeps a 32-bit interval `[x1, x2]`. For each bit, with predicted probability `p` (12 bits) that it is a 1:

```
xmid = x1 + ((x2 − x1) >> 12) × p
if bit == 1:  x2 = xmid
else:         x1 = xmid + 1
while the top bytes of x1 and x2 are equal:
    output that byte; shift both left by 8 (x2 gets 0xFF shifted in)
```

The interval shrinks in proportion to the probability of what actually happened, so a bit costs about −log₂(p) bits of output. A bit predicted with 99.9% confidence costs about 0.0014 bits.

This is a *carry-less* coder (in the style of lpaq): once the top bytes of `x1` and `x2` agree, no later narrowing can change them, so they are emitted immediately. At the end, the four bytes of `x1` are flushed. The decoder mirrors the encoder exactly, holding the next 32 bits of the stream in `x` and comparing `x ≤ xmid` to recover each bit. Reads past the end of the payload return zero.

The 12-bit probability limits the best case to about 1/4096, or 0.00035 bits per bit. That only matters on extremely redundant data, such as long runs of zeros. A 16-bit variant was tried and did worse overall (see §12).

---

## 5. Stored-block fallback

After coding, if `payload length + 8 ≥ block length`, the coded form is discarded and the block is written raw. The 8 bytes are the primary index and the payload length, which a stored block doesn't need.

This bounds the worst case. Incompressible input such as random, encrypted or already-compressed data grows by only **9 bytes per block**, plus the 6-byte file header and the 1-byte end marker.

---

## 6. Decompression and the inverse BWT

The decoder rebuilds the same model, decodes `n` bytes of BWT output bit by bit, then inverts the BWT in **O(n)**. The inversion relies on one key property:

> The *k*-th occurrence of a character `c` in the last column is the same text position as the *k*-th occurrence of `c` in the first column.

The first column is just the sorted last column, so it can be described by cumulative counts. Treat the stored bytes as an `(n + 1)`-entry last column with `$` re-inserted at the primary index. The first column then starts with `$` at row 0, followed by each byte value's range. Building the **T-vector** (ψ):

```
start[c] = 1 + (number of bytes < c)     // row 0 is '$'
next[0]  = primary                        // '$' in F ↔ '$' in L
for each row r ≠ primary, in order, with last byte c:
    next[start[c]++] = r
```

`next[j]` is the row whose *last* character is the same occurrence as the *first* character of row `j`. The primary row holds the whole block (rotation 0). Following `next` from it visits rotations 1, 2, 3, …, and the last character of rotation *k + 1* is `s[k]`:

```
curr = primary
for k in 0..n−1:
    curr = next[curr]
    s[k] = last byte of row curr
```

Using the `banana` example, this produces b, a, n, a, n, a.

---

## 7. File format

All multi-byte integers are **big-endian** (Java `DataOutputStream`).

### File header

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | Magic: ASCII `BWTS` (`42 57 54 53`) |
| 4 | 1 | Format version: `3` |

### Blocks

Blocks follow the header back to back, each starting with a one-byte type.

**End of stream** (type `0`): the single byte `0`.

**BWT-coded block** (type `1`)

| Size | Field |
|---|---|
| 1 | `1` |
| 4 | Original length `n` (1 … 256,000,000) |
| 4 | CRC-32 of the original bytes |
| 4 | Primary index (1 … n) |
| 4 | Payload length in bytes (< n) |
| *payload length* | Arithmetic-coded BWT output (the model is reset at the start of every block) |

**Stored block** (type `2`)

| Size | Field |
|---|---|
| 1 | `2` |
| 4 | Original length `n` |
| 4 | CRC-32 of the original bytes |
| *n* | Raw bytes |

Block size is not recorded in the header. Each block carries its own length, so the decoder doesn't need to know what block size the compressor used. An empty input produces just the header and the end marker: 6 bytes.

---

## 8. Integrity and error handling

The decoder validates input at every level and fails with a clear message and exit status 1. It never writes a block that fails its checks.

| Check | Message |
|---|---|
| Missing or wrong magic number | `not a BWTSorter file (bad magic)` |
| Older or newer format version | `unsupported format version N (made by an older BWTSorter)` |
| Length, primary index or payload length out of range | `corrupt block header` |
| Unknown block type | `unknown block type N` |
| CRC-32 mismatch | `CRC mismatch in block N - data is corrupt` |
| Input ends mid-block or with no end marker | `unexpected end of file - compressed data is truncated` |

An arithmetic-coded stream has no internal structure to violate: any bit sequence decodes to *something*. A damaged payload therefore decodes to garbage of the right length, and the **CRC-32** over the original bytes is what catches it. In testing, a single flipped bit in the middle of a payload was reported as a CRC mismatch.

Blocks are written as they are verified. If a later block is corrupt, the output file will contain the earlier, verified blocks before the error is reported.

---

## 9. Complexity and memory

| Stage | Time | Working memory (block of n bytes) |
|---|---|---|
| SA-IS suffix sort | O(n) | ≈ 13n bytes (text as `int[]`, SA, LMS map, type flags) + recursion ≤ 6.5n |
| Context model + coder | O(n), ≈ 8 model evaluations per byte | ≈ 100 MB of tables, independent of n |
| Inverse BWT | O(n) | ≈ 6n bytes (T-vector + two byte arrays) |

At the default 32 MB block size, compression runs within a **768 MB** heap (it fails at 512 MB) and decompression within **256 MB**. Java's default maximum heap is a quarter of physical memory, so a machine with 4 GB of RAM or more runs the default settings without adjustment. On smaller machines, use a smaller block size (`java BWTSorter -c in out 8`) or raise the heap (`java -Xmx2g …`). The program reports an out-of-memory condition with that advice rather than a stack trace.

Unlike the sort, the model's cost is the same per byte regardless of content. It is the dominant cost in both directions, which is why decompression is about as slow as compression. That is the usual signature of context-mixing compressors, and the opposite of LZ-family tools like xz, which decompress many times faster than they compress.

---

## 10. Benchmarks

All results are on the **Silesia corpus**, the standard benchmark for general-purpose compressors: 12 files, 211.9 MB, covering English text, a dictionary, XML, source code, databases, executables, medical images and astronomical data. Each file was compressed and decompressed separately, and every BWTSorter output was compared byte-for-byte with the original. All 12 round-tripped correctly.

The tools compared were BWTSorter with default settings (32 MB blocks, default JVM heap), `bzip2 -9`, and `xz -9e -T1` (xz's strongest preset, single-threaded). Everything ran on a 2-core Linux cloud VM with OpenJDK 21.

### Compressed size (bytes; smallest in bold)

| File | Contents | Original | BWTSorter | bzip2 -9 | xz -9e | vs xz |
|---|---|---|---|---|---|---|
| dickens | English novels | 10,192,446 | **2,254,513** | 2,799,520 | 2,831,212 | −20.4% |
| mozilla | Tarred executables | 51,220,480 | 15,992,950 | 17,914,392 | **13,376,240** | +19.6% |
| mr | MRI image | 9,970,564 | **2,112,249** | 2,441,280 | 2,751,892 | −23.2% |
| nci | Chemical database | 33,553,445 | **1,235,199** | 1,812,734 | 1,449,272 | −14.8% |
| ooffice | x86 DLL | 6,152,192 | 2,546,137 | 2,862,526 | **2,427,224** | +4.9% |
| osdb | Database records | 10,085,684 | **2,194,465** | 2,802,792 | 2,844,556 | −22.9% |
| reymont | Polish novel (PDF text) | 6,627,202 | **988,049** | 1,246,230 | 1,315,592 | −24.9% |
| samba | Tarred source code | 21,606,400 | 3,943,855 | 4,549,759 | **3,739,524** | +5.5% |
| sao | Star catalogue (binary) | 7,251,944 | 4,664,269 | 4,940,524 | **4,425,664** | +5.4% |
| webster | Dictionary | 41,458,703 | **6,638,224** | 8,644,714 | 8,368,672 | −20.7% |
| x-ray | X-ray image | 8,474,240 | **3,647,928** | 4,051,112 | 4,491,264 | −18.8% |
| xml | XML documents | 5,345,280 | **392,043** | 441,186 | 434,892 | −9.9% |
| **Total** | | **211,938,580** | **46,609,881** | 54,506,769 | 48,456,004 | **−3.8%** |

Compression ratio over the whole corpus: **BWTSorter 4.55**, xz 4.37, bzip2 3.89.

BWTSorter produces the smallest file on **8 of the 12** files. Its total is 14.5% smaller than bzip2's and 3.8% smaller than xz's.

### Speed (whole corpus, seconds)

| | Compress | Decompress |
|---|---|---|
| BWTSorter | 148 (1.4 MB/s) | 147 (1.4 MB/s) |
| bzip2 -9 | 20 | — |
| xz -9e | 205 | — |

Decompression times for bzip2 and xz weren't measured. Typically bzip2 decompresses at roughly twice its compression speed, and xz at 50–100 MB/s.

BWTSorter compresses faster than `xz -9e` but decompresses far more slowly, because its model must be re-run in full to decode (see §9). This is the standard trade-off of context-mixing compressors: a better ratio in exchange for symmetric, slower decoding.

### Smaller synthetic tests

| Input | Size | BWTSorter | bzip2 -9 | xz -9e |
|---|---|---|---|---|
| Java source ×10 | 93,120 | 3,583 | 4,281 | 2,716 |
| Random bytes | 200,000 | 200,015 | 201,293 | 200,072 |
| `abc` × 1000 | 3,000 | 37 | 45 | 88 |
| Empty file | 0 | 6 | 14 | 32 |

---

## 11. Why it beats xz on some files and not others

**Where BWTSorter wins** (text, dictionaries, databases, images; 8 files, −10% to −25%): this data is dominated by *statistical* redundancy. Each symbol is fairly predictable from its immediate context, but long exact repeats are relatively rare. A BWT followed by a good adaptive model captures every context of every length at once. xz's LZ77 core can only exploit exact repeats; its literal and context coding (LZMA) is good, but not as good as a dedicated model on sorted contexts.

**Where xz wins** (4 files):

- **mozilla, ooffice** (executables, +5% to +20%). In machine code, the same call instruction to the same function encodes a *different* relative offset at each call site. That breaks up the contexts the BWT relies on. LZ matching with "repeat last offset" codes handles the structured, operand-heavy layout of code better. mozilla is also a tar of many dissimilar files, which favours LZ's ability to adapt instantly at file boundaries.
- **samba** (source code tarball, +5.5%). Source trees contain many long exact duplicates (licence headers, repeated boilerplate, near-identical files) that LZ77 encodes as single cheap matches. BWT handles them well too, but pays a small cost at every point where a duplicated region's sorted contexts start and end.
- **sao** (binary star catalogue, +5.4%). This consists of fixed-width records of floating-point numbers. The most useful context is often "the same field in the previous record" rather than the preceding bytes. LZ matching with repeated offsets can pick up some of that regularity; a BWT, which sorts by the bytes that immediately follow, largely loses it.

---

## 12. How the model was tuned

The model's structure was designed up front; its parameters were then tuned empirically on the BWT output of eight Silesia files (the first 12 MB of each), measuring total coded size. Each change was kept only if it reduced the total.

Changes were adopted in batches, and the total was measured at each checkpoint:

| Checkpoint | 8-file total |
|---|---|
| Initial design (mixer learning rate 10; order-1 limit 1023, order-2 255) | 21,220,340 |
| Mixer learning rate 16; order-1 limit 127 | 21,132,976 |
| Order-1 limit 20; order-2 limit 60; run-map limits 127/255 | 20,889,761 |
| Order-1 limit 12; order-2 limit 8; order-0 limit 15; final blend weights `apm2` × 2 | **20,502,958 (−3.4%)** |

Things that were tried and rejected:

| Variant | Result |
|---|---|
| Mixer learning rate 12, 20 or 24 | worse (+0.9% to +2.3%) |
| Mixer weights selected by partial byte only | worse |
| Mixer weights also selected by previous byte | worse (learns too slowly) |
| 16-bit probabilities in the coder | worse (+0.6%) |
| Dual-rate order-1 (fast + slow) | no gain |
| Order-3 input | −0.09%, 25% slower, +64 MB |
| Second mixer, averaged | −0.07%, 15% slower |

The consistent lesson was **forget fast**. Almost every gain came from making the order-n models adapt more quickly, which reflects how BWT output is a sequence of short, statistically distinct regions.

---

## 13. Version history

| Version | Pipeline | Silesia result |
|---|---|---|
| 1 (original) | Comparison-sort BWT, 100 KB blocks, MTF, byte-pair RLE | Did not round-trip (inverse BWT reversed each block); quadratic sort on repetitive data |
| 2 | Prefix-doubling BWT, 900 KB blocks, MTF, RUNA/RUNB, single Huffman table | About 15% larger than bzip2 on large text |
| 3 (current) | SA-IS BWT, 32 MB blocks, context mixing, arithmetic coding | 3.8% smaller than xz -9e and 14.5% smaller than bzip2 -9 on the whole corpus |

Each version's format is incompatible with the others, and the decoder rejects older files with a clear message.

---

## 14. Limitations and possible extensions

1. **Speed.** About 1.4 MB/s in each direction, single-threaded. The 64 MB order-2 table causes a cache miss on most bits. Blocks are independent, so compressing several blocks in parallel would scale almost linearly with cores. Within a block, the model could also be split across threads by coding separate segments with separate models, at a small cost in ratio.
2. **Executable code.** x86 machine code compresses poorly with any BWT, because relative branch addresses make identical calls look different. xz handles this better, and has an optional filter (`--x86`) that converts addresses to absolute form first. A similar E8/E9 filter in front of the BWT would help on `ooffice`- and `mozilla`-like data.
3. **Structured binary data** (tables of fixed-width records, images such as `x-ray`, audio). Delta or record-aware filters before the BWT can help considerably.
4. **Richer modelling.** An order-3 input and a second mixer each gained under 0.1% for a 15–25% slowdown. Further gains would come from bigger changes: modelling ranks rather than bytes (as bsc's QLFC does), or two-pass parameter optimisation per block.
5. **Stream support.** Accepting `-` for stdin and stdout would let the tool sit in a shell pipeline.
6. **Whole-stream CRC** in the end marker, to detect blocks being dropped or reordered.

---

## 15. Code map

| Method / class | Role |
|---|---|
| `main`, `printUsage` | Command-line handling, block size option, timing, memory advice |
| `compress`, `compressBlock` | Header, block loop, coded-vs-stored decision |
| `transformBWT` | BWT with virtual sentinel, read off the suffix array |
| `SuffixArray.build`, `induce` | SA-IS suffix array construction |
| `Logistic` | Integer stretch/squash tables |
| `StateMap` | Adaptive per-context probabilities with hit counts |
| `APM` | Secondary estimation (interpolated probability refinement) |
| `Predictor` | Context selection, mixer, APM chain |
| `ArithmeticCoder.encode` / `decode` | Carry-less binary arithmetic coder driven by `Predictor` |
| `decompress` | Header and block parsing, validation, CRC check |
| `inverseBWT` | O(n) reconstruction via the T-vector with sentinel |

---

## Credits

- **Mario Gianota** — architect
- **Claude** (an AI model by Anthropic) — implementation and documentation

---

## References

- M. Burrows and D. J. Wheeler, *A Block-sorting Lossless Data Compression Algorithm*, DEC SRC Research Report 124, 1994.
- G. Nong, S. Zhang and W. H. Chan, *Two Efficient Algorithms for Linear Time Suffix Array Construction*, IEEE Transactions on Computers, 2011 (SA-IS).
- AtCoder Library, `string.hpp` — reference SA-IS implementation.
- M. Mahoney, *Data Compression Explained* — context mixing, StateMaps, mixers and APMs; the lpaq compressor.
- I. Grebnov, *libbsc* — modern block-sorting compression.
- S. Deorowicz, *Silesia compression corpus*.
