# BWTSorter — Technical Notes

This document describes how BWTSorter works internally: the compression pipeline, the algorithms behind each stage, the on-disk format, the design trade-offs, and how it compares with other compressors, bzip3 and bsc in particular. It is aimed at developers and the curious. For usage instructions, see [README.md](README.md).

BWTSorter is a single-file Java program (`BWTSorter.java`, about 1,100 lines, no dependencies beyond the JDK). It is a block-sorting compressor, related to `bzip2` but with a modern pipeline: an x86 filter and LZP preprocessing, a linear-time suffix sort, large blocks, and a context-mixing model driving an arithmetic coder. This places it in the same family as `bzip3`, `bsc` and `bcm`; on the Silesia corpus it produces the smallest total of all the compressors tested (§11).

---

## Contents

1. [Pipeline overview](#1-pipeline-overview)
2. [Stage 1: Preprocessing (x86 filter and LZP)](#2-stage-1-preprocessing-x86-filter-and-lzp)
3. [Stage 2: Burrows–Wheeler Transform](#3-stage-2-burrowswheeler-transform)
4. [Stage 3: Context-mixing model](#4-stage-3-context-mixing-model)
5. [Stage 4: Binary arithmetic coder](#5-stage-4-binary-arithmetic-coder)
6. [Stored-block fallback](#6-stored-block-fallback)
7. [Decompression and the inverse BWT](#7-decompression-and-the-inverse-bwt)
8. [File format](#8-file-format)
9. [Integrity and error handling](#9-integrity-and-error-handling)
10. [Complexity and memory](#10-complexity-and-memory)
11. [Benchmarks](#11-benchmarks)
12. [BWTSorter and bzip3](#12-bwtsorter-and-bzip3)
13. [Why it wins on some files and not others](#13-why-it-wins-on-some-files-and-not-others)
14. [How it was tuned](#14-how-it-was-tuned)
15. [Version history](#15-version-history)
16. [Limitations and possible extensions](#16-limitations-and-possible-extensions)
17. [Code map](#17-code-map)

---

## 1. Pipeline overview

Input is split into blocks, **32 MB by default** (configurable from 1 to 256 MB), and each block is compressed independently. Several blocks are worked on at once, as many as there are cores and memory allows (§10).

```
           ┌────────────┐   ┌──────────┐   ┌───────────────┐   ┌──────────────────────┐   ┌────────────┐
 block ──▶ │ x86 filter │──▶│   LZP    │──▶│ BWT via SA-IS │──▶│ context-mixing model │──▶│ arithmetic │──▶ payload
 (bytes)   │ (if x86    │   │ (if it   │   │ (suffix sort) │   │ (predicts each bit)  │──▶│   coder    │
           │  code)     │   │  helps)  │   └───────────────┘   └──────────────────────┘   └────────────┘
           └────────────┘   └──────────┘           │                      ▲  bit                │
                                             primary index                └─────────────────────┘
                                                                              if payload ≥ block size
                                                                                → store block raw
```

The x86 filter makes machine code more repetitive, and LZP removes long repeated strings, so the later stages have less to do. The BWT is a *permutation*: it makes the data no smaller, only far more predictable. The model then estimates, for every bit of the BWT output, the probability that it is a 1. The arithmetic coder spends almost exactly −log₂(p) bits on each bit it codes, so a better model translates directly into a smaller file.

Decompression runs the identical model in lock-step with an arithmetic decoder to recover the BWT output, inverts the BWT, expands any LZP markers and reverses the x86 filter.

---

## 2. Stage 1: Preprocessing (x86 filter and LZP)

Two optional transforms run before the sort. Each is applied only when it should help, and each is recorded in the block type so the decoder knows to reverse it.

### The x86 filter

**What it fixes.** In x86 machine code, a CALL instruction (opcode `E8`) or JMP (`E9`) is followed by a 4-byte address *relative to the next instruction*. Every call to the same function therefore stores a different number, and identical instructions look different to the sort. The filter adds each instruction's position to its offset, turning it into the target's absolute address. Calls to the same function become identical byte strings, which the BWT and LZP then exploit.

**What it converts.** Only plausible offsets: those whose top byte is `00` or `FF`, meaning within 16 MB either way. The converted value is kept to the same 25-bit signed range, so its top byte is again `00` or `FF` and the decoder recognises exactly the same sites. This is the approach of the classic BCJ filters in 7-Zip and xz.

**One subtlety: overlapping candidates.** After *any* `E8`/`E9` opcode, the scan skips the next four bytes, whether it converted them or not. Without that rule, converting the instruction at position *i* + 1 could rewrite a byte the decoder reads when judging the opcode at position *i*, and a file could fail to decompress. The filter was fuzzed on 4,000 adversarial inputs dense with `E8`, `E9`, `00` and `FF` bytes, including one of 40 MB, and every one round-tripped.

**Detection.** Each block is scanned once, counting plausible call/jump sites per KB. At 2 or more, the block is treated as x86 code:

| Data | Sites per KB | Filter used | Effect of the filter |
|---|---|---|---|
| ooffice (32-bit Windows DLL) | 28.8 | yes | −15.6% |
| 30 MB of 64-bit Linux programs | 16.6 | yes | −7.1% |
| libjvm.so (64-bit Linux library) | 12.1 | yes | −6.6% |
| 10% x86 DLL + 90% chemical database | ≈ 3 | yes | −8.7% |
| 25% x86-64 library + 75% dictionary | ≈ 3 | yes | −2.5% |
| mozilla (Alpha executables) | 0.7 | no | (+0.8% if forced) |
| Random data | 0.06 | no | |
| Other Silesia files | 0.00–0.16 | no | |

Silesia's mozilla file is a tar of programs built for DEC's **Alpha** processor (COFF executables for Tru64 UNIX, confirmed by inspecting the archive), so an x86 filter has nothing to fix there. Forced onto it, the filter only scrambles 0.7 accidental matches per KB and costs 0.8%.

### LZP

**LZP** (Lempel–Ziv Prediction, Bloom 1996) removes long repeated strings before the sort. It is the one structural idea BWTSorter borrows from bzip3 and bsc, which both use it; the implementation and its tuning are BWTSorter's own.

#### How it works

At every position, the previous 4 bytes are hashed into a table of 2²⁰ entries that remembers where that context was last seen. That earlier position is a *prediction*: perhaps the text continues the way it did last time. If it does for at least **112 bytes**, the whole repeat is replaced by a marker byte and its length. No offset is stored, because the decoder sees the same 4-byte context, looks in its own copy of the table, and makes the same prediction.

| Input | Output |
|---|---|
| Ordinary byte `b` | `b` |
| A repeat of `L` ≥ 112 bytes | marker `0xF2`, then `L − 112` as zero or more bytes of 254 followed by one byte 0–253 |
| A literal `0xF2` where a prediction exists | `0xF2 255` (escaped) |
| A literal `0xF2` where no prediction exists | `0xF2` (no escape needed: the decoder cannot mistake it) |

Positions inside a repeat are not added to the table; the decoder skips them too, so both sides keep identical tables. The encoder checks the byte at offset 111 before scanning a candidate: a usable repeat must agree there, so most candidates are rejected with one comparison.

If LZP doesn't make a block smaller, it is skipped, and the block is sorted as it is.

#### Why it helps a BWT

A passage repeated *k* times puts *k* copies of each of its contexts side by side after sorting. Each copy is cheap for the model, but not free, and the places where the repeated region's contexts start and end cost real bits. Replacing the later copies with a two-byte marker is cheaper still.

#### Why 112 bytes, not bzip3's 40

Short repeats are where BWTSorter's model is strongest. Replacing a 40-byte repeat with a marker breaks up contexts that the sort and the model would have coded almost for free. On Silesia, a 32-byte minimum made the total *worse* (+0.22%), 40 was neutral (−0.02%), and 96–160 were best. 112 gave −0.59% (§14).

#### Effect on Silesia

| File | Without LZP | With LZP | Change |
|---|---|---|---|
| samba | 3,943,855 | 3,862,504 | −2.06% |
| nci | 1,235,199 | 1,218,526 | −1.35% |
| mozilla | 15,992,950 | 15,847,747 | −0.91% |
| xml | 392,043 | 390,240 | −0.46% |
| ooffice | 2,546,137 | 2,535,420 | −0.42% |
| dickens | 2,254,513 | 2,246,292 | −0.36% |
| webster | 6,638,224 | 6,626,221 | −0.18% |
| reymont | 988,049 | 987,293 | −0.08% |
| mr | 2,112,249 | 2,114,424 | +0.10% |
| osdb, sao, x-ray | | | unchanged (skipped) |
| **Total** | **46,609,881** | **46,335,329** | **−0.59%** |

LZP also made BWTSorter about **8–10% faster** in both directions, because the expensive model has less data to process.

---

## 3. Stage 2: Burrows–Wheeler Transform

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

## 4. Stage 3: Context-mixing model

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

## 5. Stage 4: Binary arithmetic coder

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

The 12-bit probability limits the best case to about 1/4096, or 0.00035 bits per bit. That only matters on extremely redundant data, such as long runs of zeros. A 16-bit variant was tried and did worse overall (see §14).

---

## 6. Stored-block fallback

After coding, if the payload plus its extra header fields (8 bytes, or 12 with LZP) would be at least as large as the block, the coded form is discarded and the block is written raw.

This bounds the worst case. Incompressible input such as random, encrypted or already-compressed data grows by only **9 bytes per block**, plus the 6-byte file header and the 1-byte end marker.

---

## 7. Decompression and the inverse BWT

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

For an LZP block, the result is the LZP stream, which is then expanded: the decoder rebuilds the same context table as it writes, and copies each marked repeat from the predicted position, byte by byte (a repeat may overlap the bytes it is copying from). Finally, an x86 block has the filter reversed: the same scan, subtracting each position instead of adding it.

---

## 8. File format

All multi-byte integers are **big-endian** (Java `DataOutputStream`).

### File header

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | Magic: ASCII `BWTS` (`42 57 54 53`) |
| 4 | 1 | Format version: `5` |

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

**LZP + BWT-coded block** (type `3`)

| Size | Field |
|---|---|
| 1 | `3` |
| 4 | Original length `n` |
| 4 | CRC-32 of the original bytes |
| 4 | LZP length `m` (4 … n − 1) |
| 4 | Primary index (1 … m) |
| 4 | Payload length in bytes (< n) |
| *payload length* | Arithmetic-coded BWT of the LZP stream |

**x86-filtered blocks** (types `4` and `5`): laid out exactly like types `1` and `3` respectively; the decoder reverses the x86 filter after the other stages.

**Stored block** (type `2`)

| Size | Field |
|---|---|
| 1 | `2` |
| 4 | Original length `n` |
| 4 | CRC-32 of the original bytes |
| *n* | Raw bytes |

Version 4 added the type-3 block and version 5 added types 4 and 5. Older files are therefore valid newer files, and the current decoder reads versions 3, 4 and 5. Files from versions 1 and 2 are rejected with a clear message.

Block size is not recorded in the header. Each block carries its own length, so the decoder doesn't need to know what block size the compressor used. An empty input produces just the header and the end marker: 6 bytes.

---

## 9. Integrity and error handling

The decoder validates input at every level and fails with a clear message and exit status 1. It never writes a block that fails its checks.

| Check | Message |
|---|---|
| Missing or wrong magic number | `not a BWTSorter file (bad magic)` |
| Older or newer format version | `unsupported format version N (made by an older/newer BWTSorter)` |
| Length, primary index or payload length out of range | `corrupt block header` |
| Unknown block type | `unknown block type N` |
| Malformed LZP stream | `LZP stream ended early`, `LZP repeat overruns the block`, `LZP stream has trailing data` |
| CRC-32 mismatch | `CRC mismatch in block N - data is corrupt` |
| Input ends mid-block or with no end marker | `unexpected end of file - compressed data is truncated` |

An arithmetic-coded stream has no internal structure to violate: any bit sequence decodes to *something*. A damaged payload therefore decodes to garbage of the right length, and the **CRC-32** over the original bytes is what catches it. In testing, a single flipped bit in the middle of a payload was reported as a CRC mismatch; in an LZP block, the same damage can be caught even earlier, as a malformed LZP stream.

Blocks are written as they are verified. If a later block is corrupt, the output file will contain the earlier, verified blocks before the error is reported.

---

## 10. Complexity and memory

| Stage | Time | Working memory (block of n bytes) |
|---|---|---|
| x86 detection + filter | O(n): one scan to count call sites; one more to convert them, only for x86 blocks | n bytes (a filtered copy), only for x86 blocks |
| LZP | O(n): one hash lookup per position, usually one byte comparison | n bytes of output + a 4 MB table |
| SA-IS suffix sort | O(n) | ≈ 13n bytes (text as `int[]`, SA, LMS map, type flags) + recursion ≤ 6.5n |
| Context model + coder | O(n), ≈ 8 model evaluations per byte | ≈ 100 MB of tables, independent of n |
| Inverse BWT | O(n) | ≈ 6n bytes (T-vector + two byte arrays) |

At the default 32 MB block size (rechecked with LZP), compression runs within a **768 MB** heap (it fails at 512 MB) and decompression within **256 MB**. Java's default maximum heap is a quarter of physical memory, so a machine with 4 GB of RAM or more runs the default settings without adjustment. On smaller machines, use a smaller block size (`java BWTSorter -c in out 8`) or raise the heap (`java -Xmx2g …`). The program reports an out-of-memory condition with that advice rather than a stack trace.

### Threads and memory

Blocks share nothing (the model starts afresh in each), so they are compressed and decompressed in parallel on a pool of worker threads. The main thread reads blocks in order, hands each to a worker, and writes the results strictly in order. The output is therefore byte-for-byte identical whatever the number of threads, which was checked on single- and multi-block files.

The number of threads is the smallest of three numbers: the cores available, the number of blocks, and how many blocks fit in 90% of Java's memory limit at once. The per-block allowances leave a margin above measured peaks (a 32 MB block compressed in a 704 MB heap but not 640 MB, and decompressed in 192 MB but not 160 MB):

| | Memory allowed per block in flight | At 32 MB blocks |
|---|---|---|
| Compressing | 21 × block size + 96 MB | ≈ 770 MB |
| Decompressing | 5 × block size + 48 MB | ≈ 210 MB |

Java's default limit is a quarter of physical memory, so at the default block size an 8 GB Mac compresses up to 2 blocks at a time and a 16 GB Mac up to 5, cores permitting. When memory rather than cores is the limit, the program says so and suggests a heap size, for example `java -Xmx8g BWTSorter …`. A smaller block size also lets more blocks run at once. Decompression needs far less memory, so it normally uses every core.

Measured on a 2-core VM, with the whole Silesia corpus joined into one 212 MB file (7 blocks):

| | 1 thread | 2 threads | Speed-up |
|---|---|---|---|
| Compress (3 GB heap) | 181.7 s | 98.6 s | 1.84× |
| Decompress (default heap) | 141.1 s | 81.6 s | 1.73× |

With more cores the gain continues, up to the number of blocks: a file smaller than one block uses one core. If a block fails its checks during decompression, every block before it is still written out in order before the error is reported, as in the single-threaded version.

### Cost per byte

Unlike the sort, the model's cost is the same per byte regardless of content. It is the dominant cost in both directions, which is why decompression is about as slow as compression. That is the usual signature of context-mixing compressors, and the opposite of LZ-family tools like xz, which decompress many times faster than they compress.

---

## 11. Benchmarks

All results are on the **Silesia corpus**, the standard benchmark for general-purpose compressors: 12 files, 211.9 MB, covering English text, a dictionary, XML, source code, databases, executables, medical images and astronomical data. Each file was compressed and decompressed separately with every tool. Every output was compared byte-for-byte with the original, and all 180 round-trips (15 configurations × 12 files) succeeded.

### The contenders

Each tool was run at its strongest standard setting and **single-threaded**, BWTSorter included, so that the speeds compare like with like (BWTSorter's multi-core speed-up is in §10).

| Tool | Version | Setting | Family |
|---|---|---|---|
| **BWTSorter** | 5 | defaults (32 MB blocks) | x86 filter + LZP + BWT + context mixing |
| bzip3 | 1.5.5 | `-b 16` (default), `-b 32` and `-b 128`, all `-j 1` | RLE + LZP + BWT + context mixing |
| bsc | 3.4.0 | `-b32e2`, and `-b32pe2` (preprocessing off) | BWT + adaptive QLFC; the state of the art in block sorting |
| 7-Zip PPMd | 26.05 | `-m0=PPMd -mx9` | Prediction by partial matching |
| xz | 5.4.5 | `-9e -T1` | LZMA2 |
| 7-Zip LZMA2 | 26.05 | `-mx9 -mmt1` | LZMA2 (7-Zip's own encoder; its executable filters are chosen by file extension, so none were applied to Silesia's extension-less files) |
| brotli | 1.2.0 | `-q 11 -w 24` | LZ77 + context modelling (used on the web) |
| zstd | 1.6.0 | `-19`, and `--ultra -22` | LZ77 + finite-state entropy |
| bzip2 | 1.0.8 | `-9` | BWT + Huffman |
| gzip | 1.12 | `-9` | Deflate (the same method as `.zip`) |
| lz4 | 1.10.1 | `-12` (HC) | LZ77, built for speed |

Everything ran on 2-core Linux cloud VMs, with BWTSorter on OpenJDK 21. Absolute speeds will differ on other machines, but the ratios between tools should hold roughly.

### Whole corpus

Ranked by total compressed size (smaller is better):

| Rank | Tool | Total size | Ratio | Compress | Decompress |
|---|---|---|---|---|---|
| 1 | **BWTSorter** | **45,940,478** | **4.61** | 1.35 MB/s | 1.50 MB/s |
| 2 | bzip3 `-b 128` | 46,426,530 | 4.57 | 7.0 MB/s | 6.8 MB/s |
| 3 | bzip3 `-b 32` | 46,686,169 | 4.54 | 7.7 MB/s | 7.4 MB/s |
| 4 | bsc `-b32e2` | 46,789,246 | 4.53 | 14.0 MB/s | 15.2 MB/s |
| 5 | bsc `-b32pe2` | 46,898,896 | 4.52 | 13.6 MB/s | 15.1 MB/s |
| 6 | bzip3 `-b 16` | 46,929,960 | 4.52 | 5.7 MB/s | 7.5 MB/s |
| 7 | 7-Zip PPMd | 47,684,664 | 4.44 | 3.5 MB/s | 3.5 MB/s |
| 8 | xz `-9e` | 48,456,004 | 4.37 | 1.0 MB/s | 56 MB/s |
| 9 | 7-Zip LZMA2 | 48,757,480 | 4.35 | 1.5 MB/s | 61 MB/s |
| 10 | brotli `-q 11` | 49,564,563 | 4.28 | 0.4 MB/s | 194 MB/s |
| 11 | zstd `--ultra -22` | 52,369,178 | 4.05 | 1.1 MB/s | 349 MB/s |
| 12 | zstd `-19` | 52,895,350 | 4.01 | 1.5 MB/s | 412 MB/s |
| 13 | bzip2 `-9` | 54,506,769 | 3.89 | 11.1 MB/s | 21 MB/s |
| 14 | gzip `-9` | 67,631,990 | 3.13 | 9.5 MB/s | 125 MB/s |
| 15 | lz4 `-12` | 77,375,887 | 2.74 | 9.0 MB/s | 165 MB/s |

Speeds are the original size divided by the total wall-clock time over all 12 files, including process start-up (which costs BWTSorter about 0.1 s per file for the JVM). BWTSorter was timed (as version 4; version 5 adds only a fast scan per block) on a later VM that ran about 10% slower than the one used for the other tools: version 3, timed on that same VM, managed 1.25 and 1.36 MB/s.

**BWTSorter produces the smallest total of all.** At its default settings it is 1.0% smaller than bzip3 at bzip3's strongest tested setting, 1.8% smaller than bsc, 3.7% smaller than PPMd, 5.2% smaller than xz, 7.3% smaller than brotli, 12.3% smaller than zstd at its maximum setting, 15.7% smaller than bzip2 and 32% smaller than gzip.

### File by file

Compressed size in bytes. The smallest among the columns shown is in **bold**. bzip3 is shown at `-b 32`, the same block size as BWTSorter. The last column names the winner across all fifteen configurations tested.

| File | Contents | BWTSorter | bzip3 `-b 32` | bsc `-b32e2` | PPMd | xz | brotli | zstd `-22` | bzip2 | gzip | Best of all |
|---|---|---|---|---|---|---|---|---|---|---|---|
| dickens | English novels | 2,246,292 | **2,233,934** | 2,259,134 | 2,280,125 | 2,831,212 | 2,827,777 | 2,849,068 | 2,799,520 | 3,851,823 | bzip3 (all settings) |
| mozilla | Tarred executables (Alpha) | 15,847,747 | 15,931,982 | 16,001,774 | 15,911,902 | **13,376,240** | 13,872,265 | 14,914,120 | 17,914,392 | 18,994,142 | 7-Zip LZMA2 |
| mr | MRI image | **2,114,424** | 2,119,990 | 2,206,012 | 2,320,767 | 2,751,892 | 2,823,136 | 3,102,402 | 2,441,280 | 3,673,940 | BWTSorter |
| nci | Chemical database | 1,218,526 | 1,366,363 | **1,210,336** | 1,484,569 | 1,449,272 | 1,519,768 | 1,579,587 | 1,812,734 | 2,987,533 | bsc `-b32pe2` |
| ooffice | x86 DLL | **2,140,569** | 2,526,882 | 2,559,108 | 2,496,139 | 2,427,224 | 2,478,855 | 2,595,256 | 2,862,526 | 3,090,442 | BWTSorter |
| osdb | Database records | **2,194,465** | 2,261,854 | 2,224,780 | 2,384,175 | 2,844,556 | 2,816,278 | 3,100,124 | 2,802,792 | 3,716,342 | BWTSorter |
| reymont | Polish novel (PDF text) | 987,293 | **980,459** | 982,364 | 1,054,299 | 1,315,592 | 1,332,158 | 1,348,522 | 1,246,230 | 1,820,834 | bzip3 (all settings) |
| samba | Tarred source code | 3,862,504 | 3,918,956 | 3,909,928 | 3,815,315 | **3,739,524** | 3,766,340 | 3,866,386 | 4,549,759 | 5,408,272 | xz |
| sao | Star catalogue (binary) | 4,664,269 | 4,673,307 | 4,679,764 | 4,783,825 | **4,425,664** | 4,586,092 | 5,000,576 | 4,940,524 | 5,327,041 | 7-Zip LZMA2 |
| webster | Dictionary | 6,626,221 | 6,609,482 | **6,606,282** | 6,942,891 | 8,368,672 | 8,428,575 | 8,431,645 | 8,644,714 | 12,061,624 | bzip3 `-b 128` |
| x-ray | X-ray image | **3,647,928** | 3,657,083 | 3,756,584 | 3,850,893 | 4,491,264 | 4,682,753 | 5,129,828 | 4,051,112 | 6,037,713 | BWTSorter |
| xml | XML documents | 390,240 | 405,877 | 393,180 | **359,764** | 434,892 | 430,566 | 451,664 | 441,186 | 662,284 | 7-Zip PPMd |
| **Total** | | **45,940,478** | 46,686,169 | 46,789,246 | 47,684,664 | 48,456,004 | 49,564,563 | 52,369,178 | 54,506,769 | 67,631,990 | **BWTSorter** |

Wins across all fifteen configurations: BWTSorter 4 files, bzip3 3, 7-Zip LZMA2 2, bsc 1, xz 1, PPMd 1. On ooffice, 7-Zip would have won by 2.7% (2,083,135 bytes) had it been told to use its x86 filter, which it applies automatically only to files named `.exe`, `.dll` and the like. BWTSorter has the smallest total without being the best on most individual files: it is close to the front on every one, and far from the front on none of the text, data or image files.

### What the numbers say

- **Against bzip3**, its closest relative (§12), BWTSorter is 1.6% smaller at the same 32 MB block size and wins 9 of the 12 files. With both at 128 MB blocks it is 1.7% smaller (45,627,929 against 46,426,530 bytes). bzip3 keeps the three large text files (dickens, reymont, webster), by 0.2–0.7%. bzip3 is about **five times faster** in both directions, and can use several cores.
- **Against bsc**, BWTSorter is 1.8% smaller overall and wins 9 of 12 files; bsc keeps nci, reymont and webster. bsc is about **ten times faster**. Its entropy coder (quantized local frequency coding) does far less work per byte than a bit-level context-mixing model, and it is optimised C++.
- **Against the LZ family** (xz, 7-Zip LZMA2, brotli, zstd, gzip, lz4), BWTSorter compresses text, data, images and the x86 DLL much better: 9–25% smaller than the best LZ result on each of those 9 files. It loses on the Alpha executables, the source tarball and the star catalogue (§13). Every LZ tool decompresses vastly faster, at 50–400 MB/s. That is why LZ dominates software distribution, where a file is compressed once and decompressed millions of times.
- **Against PPMd**, the other classic statistical approach, BWTSorter wins on 10 of 12 files and overall. PPMd's standout is XML, where its long, variable-order contexts capture tag structure especially well.
- **Speed.** BWTSorter's compression speed is in the same band as the maximum settings of xz, LZMA2, zstd and brotli (0.4–1.5 MB/s). Its decompression is the slowest of the group.

### Smaller synthetic tests

| Input | Size | BWTSorter | bzip2 -9 | xz -9e |
|---|---|---|---|---|
| Java source ×10 | 93,120 | **2,661** | 4,281 | 2,716 |
| Random bytes | 200,000 | **200,015** | 201,293 | 200,072 |
| `abc` × 1000 | 3,000 | **41** | 45 | 88 |
| 3 MB of zeros | 3,000,000 | **49** | **49** | 568 |
| Empty file | 0 | **6** | 14 | 32 |

The repeated Java source shows LZP at work: nine of the ten copies are replaced by markers before the sort, taking BWTSorter from 3,583 bytes (version 3) to 2,661, ahead of xz.

---

## 12. BWTSorter and bzip3

bzip3 (Kamila Szewczyk, 2022) is BWTSorter's nearest relative. BWTSorter's model was designed independently, but the two share ancestry, in Matt Mahoney's lpaq-style context mixing, and they reached several of the same answers.

**What they share**

- No Move-To-Front step: both predict the BWT output directly, one bit at a time.
- Order-0 and order-1 contexts as the base of the prediction.
- Run length as a context for a secondary refinement stage (APM).
- Fast-forgetting counters. bzip3's probabilities move ¼ or ¹⁄₁₆ of the way on each bit; BWTSorter's tuning arrived independently at limits of 8–15 on its order-n models (§14).
- A 32-bit carry-less binary arithmetic coder in the style of Mahoney's *Data Compression Explained*.
- LZP preprocessing (since BWTSorter version 4).

**Where they differ**

| | bzip3 | BWTSorter |
|---|---|---|
| Preprocessing | RLE (for pathological runs), then LZP with 40-byte minimum | x86 filter when the block is x86 code, then LZP with 112-byte minimum; no RLE, as SA-IS has no trouble with runs |
| Suffix sort | libsais (optimised C) | SA-IS, about 100 lines of Java |
| Inputs per bit | 3: order-0, order-1, and the byte two back on its own | 5: order-0, order-1, full order-2, and two run models |
| Combining them | Fixed formula, `((p0 + p1)×7 + 2×p2) / 16` | Learning mixer, with weight sets chosen by run length and partial byte |
| Refinement | 1 APM (partial byte × "run longer than 2") | 2 APMs (order-1 context; run length in 16 levels × partial byte) |
| Counters | Fixed-rate shifts | Hit-counted: new contexts learn fast, then settle |
| Threads | Several blocks in parallel (`-j`, set by the user) | Several blocks in parallel (chosen automatically from cores and memory) |

BWTSorter's richer run modelling and learning mixer pay off where the BWT output is dominated by runs: nci (−10.8% against bzip3), xml (−3.9%), osdb (−3.0%). Its x86 filter, which bzip3 lacks, adds ooffice (−15.3%). bzip3's simpler, faster model holds a small edge on large natural-language text (0.2–0.7%). The 5× speed difference is mostly the model: bzip3 does about three table lookups and one APM per bit, BWTSorter five lookups, a mixer and two APMs, and in Java rather than C.

---

## 13. Why it wins on some files and not others

**Where BWTSorter does well** (text, dictionaries, databases, images; 8 files, 9–25% smaller than the best LZ tool): this data is dominated by *statistical* redundancy. Each symbol is fairly predictable from its immediate context, but long exact repeats are relatively rare. A BWT followed by a good adaptive model captures every context of every length at once. xz's LZ77 core can only exploit exact repeats; its literal and context coding (LZMA) is good, but not as good as a dedicated model on sorted contexts.

**Where the LZ family wins** (3 files; xz and 7-Zip's LZMA2 take all three):

- **mozilla** (Alpha executables, +18.8% against 7-Zip's LZMA2). Every statistical compressor tested lands between 15.8 and 16.0 MB here: bsc, bzip3, PPMd and BWTSorter alike. LZMA's "repeat the last offset" matches suit binaries full of near-identical records and instruction sequences; a BWT, which sorts by the bytes that follow, scatters them. The x86 filter cannot help, as the code is not x86 (§2). bsc's segmentation mode, which splits a block where its content changes, gains 1.8% here, but costs 2.5% on samba.
- **samba** (source code tarball, +3.3% against xz). Source trees contain many long exact duplicates (licence headers, repeated boilerplate, near-identical files) that LZ77 encodes as single cheap matches. BWT handles them well too, but pays a small cost at every point where a duplicated region's sorted contexts start and end. LZP narrowed this gap from 5.5%, by removing the longest duplicates before the sort.
- **sao** (binary star catalogue, +5.4% against xz). This consists of fixed-width records of floating-point numbers. The most useful context is often "the same field in the previous record" rather than the preceding bytes. LZ matching with repeated offsets can pick up some of that regularity; a BWT, which sorts by the bytes that immediately follow, largely loses it.

**x86 programs beyond Silesia.** On 32-bit Windows code (ooffice), the x86 filter makes BWTSorter about 12% smaller than xz and 7-Zip as they ran in the benchmark. 7-Zip with its own x86 filter switched on is 2.7% smaller still. On 64-bit Linux code, the LZ tools keep a clear lead: on libjvm.so BWTSorter (6,836,858 bytes) is 8% behind xz and 20% behind 7-Zip, and on 30 MB of Linux programs 6% behind xz and 15% behind 7-Zip with its x86 filter. 64-bit code references data mostly through instruction-relative addresses that a CALL/JMP filter doesn't touch, which is a likely part of the reason (§16).

**Against bzip3, bsc and PPMd.** All four are statistical compressors, so they win and lose on the same kinds of data, and the differences between them are a few percent. BWTSorter's run models and bit-level mixing do best where symbol statistics are noisy or run-heavy, as in images, binary records and the chemical database. bzip3 and bsc do slightly better on large, clean natural-language text. PPMd works on the original byte order rather than sorted contexts, which helps on nested markup like XML but costs it elsewhere.

---

## 14. How it was tuned

### The model

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

### LZP

LZP's two parameters were tuned on the whole Silesia corpus, measuring total compressed size (version 3, with no LZP, totals 46,609,881).

| Minimum repeat | Table size | Silesia total | vs no LZP |
|---|---|---|---|
| 32 | 2¹⁸ | 46,714,055 | +0.22% |
| 40 (bzip3's choice) | 2¹⁸ | 46,601,203 | −0.02% |
| 64 | 2¹⁸ | 46,384,404 | −0.48% |
| 96 | 2¹⁸ | 46,345,078 | −0.57% |
| 112 | 2¹⁸ | 46,345,744 | −0.56% |
| 128 | 2¹⁸ | 46,349,256 | −0.56% |
| 160 | 2¹⁸ | 46,359,695 | −0.54% |
| 256 | 2¹⁸ | 46,391,592 | −0.47% |
| 512 | 2¹⁸ | 46,425,574 | −0.39% |
| 112 | 2¹⁶ | 46,378,329 | −0.49% |
| **112** | **2²⁰** | **46,335,329** | **−0.59%** |
| 112 | 2²² | 46,332,690 | −0.59% |

2²² beat 2²⁰ by 2.6 KB over the whole corpus, not worth a table four times the size. Short minimums hurt because they replace repeats the model would have coded almost for free; very long ones miss repeats worth removing.

### x86 detection threshold

The threshold of 2 call sites per KB sits in the gap between the measurements in §2: x86 code scores 12–29, blocks that are only 10–25% x86 code score about 3 (and still gained 2.5–8.7%), and non-x86 data scores at most 0.7. A first choice of 6 would have missed the partly-code blocks.

---

## 15. Version history

| Version | Pipeline | Silesia result |
|---|---|---|
| 1 (original) | Comparison-sort BWT, 100 KB blocks, MTF, byte-pair RLE | Did not round-trip (inverse BWT reversed each block); quadratic sort on repetitive data |
| 2 | Prefix-doubling BWT, 900 KB blocks, MTF, RUNA/RUNB, single Huffman table | About 15% larger than bzip2 on large text |
| 3 | SA-IS BWT, 32 MB blocks, context mixing, arithmetic coding | 46,609,881 bytes: smaller than bsc, PPMd, xz, brotli and zstd; level with bzip3 |
| 4 | Version 3 + LZP preprocessing | 46,335,329 bytes (−0.59%), and about 8–10% faster; smaller in total than bzip3 at every setting tested |
| 5 (current) | Version 4 + automatic x86 filter; blocks compressed and decompressed in parallel (no format change) | 45,940,478 bytes (−0.85%; ooffice −15.6%): smallest total of all 15 configurations tested (§11) |

Versions 1, 2 and 3 have incompatible formats, and the decoder rejects files from versions 1 and 2 with a clear message. Version 5 reads files from versions 3 and 4.

---

## 16. Limitations and possible extensions

1. **Speed.** About 1.4 MB/s per core in each direction: about five times slower than bzip3 and ten times slower than bsc, core for core. The 64 MB order-2 table causes a cache miss on most bits. Several blocks already run in parallel (§10), but a file of one block uses one core; splitting a block's model across threads, by coding separate segments with separate models, would help those at a small cost in ratio.
2. **64-bit x86 code.** The CALL/JMP filter covers what matters most in 32-bit code. 64-bit code also addresses data relative to the current instruction (RIP-relative operands), which the filter leaves alone; converting those needs an instruction-length decoder. Splitting call targets into a separate stream, as 7-Zip's BCJ2 does, is another option.
3. **Other processors.** xz ships filters for ARM, ARM64, PowerPC, SPARC, IA-64 and RISC-V code; the same idea applies to each. Mozilla's Alpha code would need its own.
4. **Segmentation.** Splitting a mixed block where its content changes gained bsc 1.8% on mozilla and 2.7% on ooffice, but cost 2.5% on samba, so it would have to be kept only where it helps.
5. **Structured binary data** (tables of fixed-width records, images such as `x-ray`, audio). Delta or record-aware filters before the BWT can help considerably.
6. **Richer modelling.** An order-3 input and a second mixer each gained under 0.1% for a 15–25% slowdown. Further gains would come from bigger changes: modelling ranks rather than bytes (as bsc's QLFC does), or two-pass parameter optimisation per block.
7. **Per-block LZP choice.** LZP is used whenever it shrinks a block. Compressing each block both ways and keeping the smaller would recover the 0.1% it costs on `mr`, at nearly double the compression time.
8. **Stream support.** Accepting `-` for stdin and stdout would let the tool sit in a shell pipeline.
9. **Whole-stream CRC** in the end marker, to detect blocks being dropped or reordered.

---

## 17. Code map

| Method / class | Role |
|---|---|
| `main`, `printUsage` | Command-line handling, block size option, timing, memory advice |
| `Threads` | Thread count from cores, blocks and memory; worker pool; error passing |
| `compress`, `compressBlock` | Header, block loop, x86, LZP and coded-vs-stored decisions |
| `X86.looksLikeX86`, `X86.apply` | x86 detection, and the CALL/JMP filter in both directions |
| `LZP.encode` / `decode` | LZP preprocessing and its inverse |
| `transformBWT` | BWT with virtual sentinel, read off the suffix array |
| `SuffixArray.build`, `induce` | SA-IS suffix array construction |
| `Logistic` | Integer stretch/squash tables |
| `StateMap` | Adaptive per-context probabilities with hit counts |
| `APM` | Secondary estimation (interpolated probability refinement) |
| `Predictor` | Context selection, mixer, APM chain |
| `ArithmeticCoder.encode` / `decode` | Carry-less binary arithmetic coder driven by `Predictor` |
| `decompress`, `readBlock`, `verified` | Header and block parsing (versions 3 to 5) in order, parallel decoding, CRC check |
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
- I. Grebnov, *libbsc* — modern block-sorting compression, including LZP.
- C. Bloom, *LZP: a new data compression algorithm*, Data Compression Conference, 1996.
- K. Szewczyk, *bzip3* — github.com/iczelia/bzip3.
- S. Deorowicz, *Silesia compression corpus*.
