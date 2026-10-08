# BWTSorter

`BWTSorter` is a Java data compression and decompression utility that treats data compression primarily as a sorting problem[cite: 1]. It processes data in $100\text{ KB}$ blocks using a pipeline consisting of the **Burrows-Wheeler Transform (BWT)**, **Move-To-Front (MTF)** transform, and **Run-Length Encoding (RLE)**[cite: 1].

---

## How It Works

### Compression Pipeline
1. **Burrows-Wheeler Transform (BWT):** Generates all cyclic rotations of an input block and sorts them lexicographically[cite: 1]. The last column of the sorted matrix is retained, grouping similar characters together[cite: 1]. The primary index (location of the original sequence) is saved[cite: 1].
2. **Move-To-Front (MTF):** Transforms the BWT output by replacing repeated characters with low integer ranks using a dynamically updated 256-element alphabet list[cite: 1].
3. **Run-Length Encoding (RLE):** Encodes sequences of identical bytes into `(byte, runLength)` pairs (up to 255 bytes per run) to finalize block compression[cite: 1].

### Decompression Pipeline
1. **RLE Decoding:** Decodes `(byte, runLength)` pairs to restore the MTF byte array[cite: 1].
2. **Inverse MTF:** Reconstructs the BWT byte sequence using the dynamic alphabet list[cite: 1].
3. **Inverse BWT:** Rebuilds the original byte stream in $\mathcal{O}(N)$ time using **LF-Mapping** guided by the stored primary index[cite: 1].

---

## Requirements

* **Java Development Kit (JDK):** Version 8 or higher[cite: 1].

---

## Compilation

Compile `BWTSorter.java` using `javac`:

```bash
javac BWTSorter.java


Author: Mario Gianota (mariogianota@protonmail.com) This is NOT 'free' software. Use in accordance with the MIT License.
