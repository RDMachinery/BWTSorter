# BWTSorter — Technical Notes

This document describes how BWTSorter works internally: the compression pipeline, the algorithms behind each stage, the on-disk format, and the design trade-offs. It is aimed at developers and the curious. For usage instructions, see [README.md](README.md).

BWTSorter is a single-file Java program (`BWTSorter.java`, about 570 lines, no dependencies beyond the JDK). It implements a block-sorting compressor in the same family as `bzip2`.

---

## Contents

1. [Pipeline overview](#1-pipeline-overview)
2. [Stage 1: Burrows–Wheeler Transform](#2-stage-1-burrowswheeler-transform)
3. [Stage 2: Move-To-Front](#3-stage-2-move-to-front)
4. [Stage 3: Zero-run coding (RUNA/RUNB)](#4-stage-3-zero-run-coding-runarunb)
5. [Stage 4: Canonical Huffman coding](#5-stage-4-canonical-huffman-coding)
6. [Stored-block fallback](#6-stored-block-fallback)
7. [Decompression and the inverse BWT](#7-decompression-and-the-inverse-bwt)
8. [File format](#8-file-format)
9. [Integrity and error handling](#9-integrity-and-error-handling)
10. [Complexity and memory](#10-complexity-and-memory)
11. [Benchmarks](#11-benchmarks)
12. [Comparison with bzip2](#12-comparison-with-bzip2)
13. [Limitations and possible extensions](#13-limitations-and-possible-extensions)
14. [Code map](#14-code-map)

---

## 1. Pipeline overview

Input is split into blocks of up to **900,000 bytes**, and each block is compressed independently.

```
           ┌──────────┐   ┌─────┐   ┌──────────────┐   ┌─────────┐
 block ──▶ │   BWT    │──▶│ MTF │──▶│ zero-run     │──▶│ Huffman │──▶ payload
 (bytes)   │ (sort)   │   │     │   │ RUNA / RUNB  │   │         │
           └──────────┘   └─────┘   └──────────────┘   └─────────┘
                │                                           │
          primary index                         if payload ≥ block size
                                                  → store block raw
```

None of the stages compresses much on its own. The BWT is a *permutation*: it makes the data no smaller, only more predictable. MTF turns that predictability into a skewed distribution of small numbers. Zero-run coding collapses the most common case, long stretches of zeros, and Huffman coding finally converts the skewed distribution into fewer bits.

Decompression runs the same stages in reverse order.

---

## 2. Stage 1: Burrows–Wheeler Transform

### What it computes

For a block `s` of length `n`, consider the `n` cyclic rotations `s[i..n) + s[0..i)`. Sort them lexicographically into a conceptual `n × n` matrix. The BWT output is the **last column** `L` of that matrix, plus the **primary index**: the row at which rotation 0 (the original block) landed.

Example for `banana`:

```
 row  rotation   L
  0   abanan  →  n
  1   anaban  →  n
  2   ananab  →  b
  3   banana  →  a   ← primary index = 3
  4   nabana  →  a
  5   nanaba  →  a
```

`L = "nnbaaa"`, primary index 3.

Each row is sorted by the text that *follows* its last character, so `L[i]` is the character that *precedes* the context at row `i`. In natural text, a given context is preceded by a small set of characters ("he " is very often preceded by "t"). Rows sharing a context are adjacent, so `L` consists of long stretches dominated by one or two symbols.

### How the rotations are sorted

The matrix is never built. The program sorts an `int[]` of rotation start positions.

The original implementation used a comparison sort with a character-by-character comparator. Each comparison costs up to the length of the common prefix of the two rotations, so the total is roughly *O(n log n · LCP)*. On repetitive input, which is precisely the input BWT is good at, the common prefixes are long and the sort degrades towards quadratic. 100 KB of zero bytes took about 39 seconds.

The current implementation uses **prefix doubling with radix sort** (the classic cyclic-shift suffix array construction):

1. Counting-sort rotation indices by their first byte, and assign each a rank, its equivalence class by first byte.
2. For `k = 1, 2, 4, …`: rotations are currently ordered by their first `k` bytes. The order by the first `2k` bytes is the order of the pairs `(rank[i], rank[(i + k) mod n])`.
   - Shifting each sorted start back by `k` (`sa[i] − k mod n`) produces a list already sorted by the *second* element of the pair.
   - A stable counting sort on the *first* element then gives the full pair order.
   - New ranks are assigned by comparing adjacent pairs.
3. Stop when every rank is distinct, or when `k ≥ n`.

Each pass is O(n), and there are at most ⌈log₂ n⌉ passes, so the sort is **O(n log n) regardless of content**. All arithmetic is on `int[]`, with no boxing and no comparator lambdas, and the `mod n` is a conditional subtraction.

**Periodic input.** If the block is periodic (e.g. `abcabc…`), some rotations are genuinely identical and keep tied ranks when the loop ends at `k ≥ n`. That is harmless: identical rotations have identical last characters, so the BWT does not depend on their order. And whichever tied row is recorded as the primary index, decoding from it reproduces a rotation equal to the original block.

---

## 3. Stage 2: Move-To-Front

MTF keeps a list of all 256 byte values, initially in order. For each input byte it outputs the byte's current position in the list, then moves that byte to the front.

A stretch of `L` dominated by one character becomes a stretch of zeros. A stretch alternating between two characters becomes mostly 0s and 1s. On typical text, well over half of the MTF output is 0 and the vast majority is below 10.

The implementation uses a linear search plus `System.arraycopy`. That is O(256) in the worst case per byte, but in practice the found index is tiny, so the cost is close to constant.

---

## 4. Stage 3: Zero-run coding (RUNA/RUNB)

Rather than Huffman-coding every MTF zero individually, runs of zeros are coded by their length using the scheme from bzip2.

A run of length `r ≥ 1` is written in **bijective base-2**, least-significant digit first, with two symbols:

- `RUNA` = digit 1
- `RUNB` = digit 2

```
while r > 0:
    if r is odd:  emit RUNA; r = (r − 1) / 2
    else:         emit RUNB; r = (r − 2) / 2
```

| run length | symbols |
|---|---|
| 1 | RUNA |
| 2 | RUNB |
| 3 | RUNA RUNA |
| 4 | RUNB RUNA |
| 5 | RUNA RUNB |
| 1000 | 9 symbols |

The decoder accumulates `run += digit × weight` with `weight` doubling at each step. The run ends at the first symbol that is neither `RUNA` nor `RUNB`.

Bijective numbering (no zero digit) means every run length has exactly one representation and no length needs to be stored separately. A run of length `r` costs about log₂ r symbols.

The final symbol alphabet has **258 symbols**:

| Symbol | Meaning |
|---|---|
| 0 | RUNA |
| 1 | RUNB |
| 2 … 256 | MTF value 1 … 255 (value + 1) |
| 257 | EOB (end of block) |

This stage replaces the original byte-pair RLE, which wrote a `(value, length)` pair for every run including runs of length 1. That doubled the size of non-repetitive data.

---

## 5. Stage 4: Canonical Huffman coding

### Building the code

Symbol frequencies are counted over the whole block, and a Huffman tree is built with a priority queue. Ties are broken by node index, so the tree is deterministic.

**Length limiting.** Code lengths are stored in 5 bits and capped at `MAX_CODE_LEN = 20`. If the optimal tree is deeper than that, which needs a very skewed distribution, every non-zero frequency is replaced by `1 + f/2` and the tree is rebuilt. This is the same flattening heuristic bzip2 uses. It is slightly sub-optimal compared with package-merge, but simple and effective, and in practice it rarely triggers.

### Canonical codes

Only the code **lengths** are transmitted. Codes are assigned canonically: sorted by (length, symbol), with each code one greater than the previous, left-shifted whenever the length increases:

```
code = 0
for len in 1..20:
    nextCode[len] = code
    code = (code + count[len]) << 1
```

Because the decoder can rebuild the exact same codes from the lengths alone, the code table costs only `258 × 5 = 1290 bits` (162 bytes) per block.

### Decoding

The decoder uses the bit-at-a-time canonical method from zlib's `puff.c`. It reads one bit at a time and checks whether the code read so far falls within the range of codes of the current length:

```
code = first = index = 0
for len in 1..20:
    code |= nextBit()
    if code − first < count[len]:
        return sorted[index + code − first]
    index += count[len]
    first  = (first + count[len]) << 1
    code <<= 1
```

`sorted[]` holds symbols in canonical order. This needs no tree and no lookup table, and invalid codes are detected when the loop runs out of lengths.

### Bit order

All bit I/O is **most-significant-bit first**. `BitWriter` accumulates bits in a `long` and flushes whole bytes. The final partial byte is padded with zero bits.

---

## 6. Stored-block fallback

After coding, if `payload length + 8 ≥ block length`, the coded form is discarded and the block is written raw as a *stored* block. The 8 bytes are the primary index and the payload length, which a stored block doesn't need.

This bounds the worst case. Incompressible input such as random, encrypted or already-compressed data grows by only **9 bytes per block** (type, length and CRC), plus the 6-byte file header and the 1-byte end marker. 200 KB of random data compresses to 200,015 bytes.

---

## 7. Decompression and the inverse BWT

Decoding reverses the pipeline: Huffman decode, expand zero runs into the (already zero-filled) MTF array, inverse MTF, then inverse BWT.

### The inverse BWT

Given only `L` and the primary index, the original block can be rebuilt in **O(n)**. This relies on one key property:

> The *k*-th occurrence of a character `c` in the last column `L` is the same text position as the *k*-th occurrence of `c` in the first column `F`.

That holds because rows starting with `c` are sorted by what follows `c`, and so are the rows ending with `c`, by the same ordering.

`F` is just `L` sorted, so it can be described by cumulative character counts. The program builds the **T-vector** (often called ψ):

```
count[c] = number of characters < c          (start of c's range in F)
for i in 0..n−1:
    next[count[L[i]]++] = i
```

`next[j]` is the row whose *last* character is the same occurrence as the *first* character of row `j`.

Row `p = primaryIndex` is rotation 0, whose first character is `s[0]`. `next[p]` is therefore the row whose last character is that `s[0]`. That row is rotation 1, because rotation 1 ends with `s[0]`. Following `next` repeatedly visits rotations 1, 2, 3, …, and the last character of rotation *r + 1* is `s[r]`:

```
curr = primaryIndex
for i in 0..n−1:
    curr = next[curr]
    original[i] = L[curr]
```

The walk produces the block **front to back**. (The first version of this program filled the output array from the end, which reversed every block. It went unnoticed because single-byte and all-identical blocks read the same both ways.)

---

## 8. File format

All multi-byte integers are **big-endian** (Java `DataOutputStream`).

### File header

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | Magic: ASCII `BWTS` (`42 57 54 53`) |
| 4 | 1 | Format version: `2` |

### Blocks

Blocks follow the header back to back. Every block starts with a one-byte type.

**End of stream** (type `0`)

| Size | Field |
|---|---|
| 1 | `0` |

**BWT-coded block** (type `1`)

| Size | Field |
|---|---|
| 1 | `1` |
| 4 | Original length `n` (1 … 900,000) |
| 4 | CRC-32 of the original bytes |
| 4 | Primary index (0 … n − 1) |
| 4 | Payload length in bytes |
| *payload length* | Huffman bitstream (below) |

**Stored block** (type `2`)

| Size | Field |
|---|---|
| 1 | `2` |
| 4 | Original length `n` |
| 4 | CRC-32 of the original bytes |
| *n* | Raw bytes |

### Huffman bitstream (inside a coded block)

| Bits | Field |
|---|---|
| 258 × 5 | Code length for symbols 0 … 257, in symbol order (0 = unused) |
| variable | Coded symbols, ending with EOB |
| 0–7 | Zero padding to a byte boundary |

An empty input file produces a 6-byte header followed by a lone end marker: 6 bytes in total.

Version 1 files, from the original implementation, had no header and are not readable by version 2.

---

## 9. Integrity and error handling

The decoder validates input at every level and fails with a clear message and exit status 1. It never writes a block that fails its checks.

| Check | Message |
|---|---|
| Missing or wrong magic number | `not a BWTSorter file (bad magic)` |
| Unknown version | `unsupported format version N` |
| Length, primary index or payload length out of range | `corrupt block header` |
| Unknown block type | `unknown block type N` |
| Code length > 20 | `corrupt Huffman table` |
| Bit pattern matches no code | `invalid Huffman code` |
| Payload exhausted before EOB | `Huffman stream ended early` |
| Decoded length ≠ declared length | `block overflows declared length` / `block shorter than declared length` |
| CRC-32 mismatch | `CRC mismatch in block N - data is corrupt` |
| Input ends mid-block or with no end marker | `unexpected end of file - compressed data is truncated` |

Each block's CRC-32 (`java.util.zip.CRC32`) is computed over the *original* bytes. It catches any corruption that survives the structural checks, as well as bugs in the transform itself.

Blocks are written as they are verified. If a later block is corrupt, the output file will contain the earlier, verified blocks before the error is reported.

---

## 10. Complexity and memory

Per block of `n` bytes:

| Stage | Time | Working memory |
|---|---|---|
| Rotation sort | O(n log n) | 4 `int[]` arrays ≈ 16n bytes |
| BWT output, MTF | O(n) | 2n bytes |
| Zero-run + Huffman encode | O(n) | `int[]` symbol stream ≈ 4n bytes |
| Huffman decode | O(n · code length) | 2n bytes |
| Inverse BWT | O(n) | `int[]` T-vector ≈ 4n bytes + 2n |

At the 900 KB block size, peak heap use is roughly **20 MB when compressing** and **8 MB when decompressing**, well within JVM defaults. Memory does not grow with file size, because blocks are streamed one at a time.

---

## 11. Benchmarks

Measured on a Linux cloud VM with OpenJDK, including JVM start-up time. Every file was round-tripped and compared byte-for-byte.

| Input | Size | BWTSorter | bzip2 -9 | gzip -9 |
|---|---|---|---|---|
| Java source repeated ×10 | 93,120 | 4,325 | 4,281 | 3,413 |
| Random word prose | 2,689,604 | 534,079 | 455,072 | 702,631 |
| Mixed binary | 2,012,000 | 302,409 | 303,992 | 305,387 |
| Random bytes | 200,000 | 200,015 | 201,293 | 200,064 |
| Zeros | 2,000,000 | 552 | 49 | 1,986 |
| `abc` × 1000 | 3,000 | 191 | 45 | 54 |
| Empty | 0 | 6 | 14 | 30 |
| Mixed text corpus | 13,481,576 | 2,184,173 | 1,871,492 | — |

Speed on the 13.5 MB corpus: **5.4 s to compress, 1.1 s to decompress**. bzip2 -9 took 1.4 s to compress.

Before the rewrite, the original implementation took 54 s on the 93 KB file, expanded random data to 199% of its size, and failed to round-trip anything except single-byte and constant input.

---

## 12. Comparison with bzip2

BWTSorter follows bzip2's design closely but omits several refinements:

| Feature | bzip2 | BWTSorter |
|---|---|---|
| Block size | 100–900 KB (`-1`…`-9`) | fixed 900 KB |
| Initial RLE (runs ≥ 4) | yes (RLE1) | no; not needed, since the sort is O(n log n) |
| Rotation sort | tuned multi-key quicksort with fallback | prefix doubling + radix sort |
| MTF symbol set | only symbols present in the block | all 256 |
| Zero-run coding | RUNA/RUNB | RUNA/RUNB |
| Huffman tables | 2–6 per block, chosen every 50 symbols | 1 per block |
| Code-length header | symbol bitmap + delta-coded lengths | fixed 5 bits × 258 |
| Integrity | per-block and whole-stream CRC | per-block CRC |

The ~15% gap on large text comes almost entirely from the **single Huffman table**. The statistics of MTF output vary across a block: dense, highly repetitive regions alternate with sparser ones. bzip2's multiple tables, selected per 50-symbol group and refined iteratively, adapt to that. The gap on tiny or ultra-repetitive inputs comes from the fixed **162-byte** code-length header, which bzip2 typically shrinks to a few dozen bytes.

---

## 13. Limitations and possible extensions

In rough order of payoff:

1. **Multiple Huffman tables.** Split the symbol stream into 50-symbol groups, maintain 2–6 tables, assign each group to the table that codes it most cheaply, re-estimate the tables from their assigned groups, and iterate a few times. Store the table selection per group (bzip2 MTF-codes the selectors too). This should close most of the gap to bzip2.
2. **Compact code-length header.** Write a 258-bit (or two-level) presence bitmap, then delta-code each used symbol's length against the previous one (`0` = same, `10` = +1, `11` = −1). This saves 100+ bytes per block, which matters for small files.
3. **Reduced MTF alphabet.** Map only the bytes present in the block, so MTF indices start smaller.
4. **Selectable block size**, e.g. `-1` … `-9`, trading ratio for memory.
5. **Faster sort.** SA-IS or divsufsort (applied to the doubled string) would bring the sort to O(n) and narrow the compression-speed gap with bzip2.
6. **Parallelism.** Blocks are independent, so both compression and decompression parallelise trivially across cores, as in `pbzip2`.
7. **Stream support.** Accept `-` for stdin/stdout so the tool can sit in a shell pipeline.
8. **Whole-stream CRC** in the end marker, to detect blocks being dropped or reordered.

---

## 14. Code map

| Method / class | Role |
|---|---|
| `main`, `printUsage` | Command-line handling |
| `compress`, `compressBlock` | Header, block loop, coded-vs-stored decision |
| `transformBWT`, `sortRotations` | BWT via prefix-doubling rotation sort |
| `transformMTF` | Move-to-front encode |
| `entropyEncode` | Zero-run coding, frequency count, Huffman output |
| `huffmanLengths`, `buildTreeLengths` | Length-limited Huffman code lengths |
| `canonicalCodes` | Canonical code assignment |
| `decompress` | Header and block parsing, validation, CRC check |
| `entropyDecode`, `decodeSymbol` | Huffman decode and zero-run expansion |
| `inverseMTF` | Move-to-front decode |
| `inverseBWT` | O(n) reconstruction via the T-vector |
| `BitWriter`, `BitReader` | MSB-first bit I/O |

---

## References

- M. Burrows and D. J. Wheeler, *A Block-sorting Lossless Data Compression Algorithm*, DEC SRC Research Report 124, 1994.
- J. Seward, *bzip2 and libbzip2* — source code and format.
- U. Manber and G. Myers, *Suffix Arrays: A New Method for On-Line String Searches*, 1990 (prefix doubling).
- M. Adler, `puff.c` in the zlib distribution — canonical Huffman decoding.
