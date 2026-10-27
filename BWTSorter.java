import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.PriorityQueue;
import java.util.zip.CRC32;

/**
 * BWTSorter
 *
 * A block-sorting compressor in the style of bzip2, written as a single readable file.
 * It treats compression as a sorting problem: sorting all rotations of a block groups
 * together characters that appear in similar contexts, and that grouping is what the
 * later stages exploit.
 *
 * COMPRESSION PIPELINE (per block of up to 900 KB):
 *  1. Burrows-Wheeler Transform. All cyclic rotations of the block are sorted using
 *     prefix doubling with radix sort: O(n log n) regardless of how repetitive the
 *     data is. The last column of the sorted rotation matrix is the BWT output, and
 *     the "primary index" records which row holds the original, unrotated block.
 *     Characters with similar following context end up next to each other, so the
 *     last column is dominated by a few locally frequent symbols.
 *  2. Move-To-Front. Locally frequent symbols become small numbers, mostly 0.
 *  3. Zero-run coding (bzip2's RUNA/RUNB). Each run of MTF zeros is written as its
 *     length in bijective base 2, so a run of length r costs about log2(r) symbols.
 *     Non-zero MTF values v become symbol v+1, and an end-of-block symbol is appended.
 *  4. Huffman coding. A canonical, length-limited Huffman code is built for the
 *     258-symbol alphabet. Small MTF values get short codes, which is where most of
 *     the compression actually comes from.
 *  5. If the coded block would not be smaller than the raw block (e.g. random or
 *     already-compressed data), the block is stored uncompressed instead.
 *
 * FILE FORMAT:
 *   "BWTS" magic, 1 version byte, then a sequence of blocks:
 *     type byte: 0 = end of stream, 1 = BWT-coded, 2 = stored
 *     int  original length
 *     int  CRC32 of the original bytes
 *     BWT-coded: int primary index, int payload length, payload (Huffman bitstream)
 *     stored:    raw bytes
 *
 * DECOMPRESSION reverses the pipeline: Huffman decode, expand zero runs, inverse MTF,
 * then inverse BWT in O(n) by following the T-vector (psi mapping) forward from the
 * primary index. Each block's CRC32 is verified before it is written.
 */
public class BWTSorter {

    private static final int BLOCK_SIZE = 900_000;
    private static final byte[] MAGIC = "BWTS".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 2;

    private static final int BLOCK_END = 0;
    private static final int BLOCK_BWT = 1;
    private static final int BLOCK_STORED = 2;

    // Symbol alphabet after zero-run coding.
    private static final int RUNA = 0;
    private static final int RUNB = 1;
    private static final int EOB = 257;          // MTF values 1..255 map to 2..256
    private static final int ALPHABET = 258;
    private static final int MAX_CODE_LEN = 20;  // fits in the 5-bit length header
    private static final int LEN_BITS = 5;

    public static void main(String[] args) {
        if (args.length < 3) {
            printUsage();
            return;
        }

        String mode = args[0];
        File inputFile = new File(args[1]);
        File outputFile = new File(args[2]);

        try {
            if ("-c".equalsIgnoreCase(mode)) {
                System.out.println("Compressing via BWT Block-Sorting...");
                compress(inputFile, outputFile);
                System.out.printf("Compression Complete -> %s (%d -> %d bytes)%n",
                        outputFile.getName(), inputFile.length(), outputFile.length());
            } else if ("-d".equalsIgnoreCase(mode)) {
                System.out.println("Decompressing via Inverted Sorting Mapping...");
                decompress(inputFile, outputFile);
                System.out.println("Decompression Complete -> " + outputFile.getName());
            } else {
                printUsage();
            }
        } catch (IOException e) {
            System.err.println("Error processing file: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  Compress:   java BWTSorter -c <input_file> <output_file>");
        System.out.println("  Decompress: java BWTSorter -d <compressed_file> <decompressed_file>");
    }

    // =========================================================================
    // COMPRESSION ENGINE
    // =========================================================================

    public static void compress(File inFile, File outFile) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(inFile));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)))) {

            out.write(MAGIC);
            out.writeByte(VERSION);

            byte[] buffer = new byte[BLOCK_SIZE];
            int bytesRead;

            // readNBytes always fills the buffer unless the input has ended,
            // so every block except the last is exactly BLOCK_SIZE.
            while ((bytesRead = in.readNBytes(buffer, 0, BLOCK_SIZE)) > 0) {
                compressBlock(buffer, bytesRead, out);
            }
            out.writeByte(BLOCK_END);
        }
    }

    private static void compressBlock(byte[] buffer, int n, DataOutputStream out) throws IOException {
        byte[] block = new byte[n];
        System.arraycopy(buffer, 0, block, 0, n);

        CRC32 crc = new CRC32();
        crc.update(block);
        int checksum = (int) crc.getValue();

        BWTResult bwt = transformBWT(block);
        byte[] mtf = transformMTF(bwt.bwtBlock);
        byte[] payload = entropyEncode(mtf);

        // 8 extra bytes (primary index + payload length) for a coded block.
        if (payload.length + 8 < n) {
            out.writeByte(BLOCK_BWT);
            out.writeInt(n);
            out.writeInt(checksum);
            out.writeInt(bwt.primaryIndex);
            out.writeInt(payload.length);
            out.write(payload);
        } else {
            out.writeByte(BLOCK_STORED);
            out.writeInt(n);
            out.writeInt(checksum);
            out.write(block);
        }
    }

    // --- BWT Transformation (Sorting Phase) ---

    private static class BWTResult {
        final byte[] bwtBlock;
        final int primaryIndex;

        BWTResult(byte[] bwtBlock, int primaryIndex) {
            this.bwtBlock = bwtBlock;
            this.primaryIndex = primaryIndex;
        }
    }

    private static BWTResult transformBWT(byte[] src) {
        int n = src.length;
        int[] rotations = sortRotations(src);

        byte[] bwt = new byte[n];
        int primaryIndex = -1;
        for (int i = 0; i < n; i++) {
            // Row holding the original, unrotated block
            if (rotations[i] == 0) primaryIndex = i;
            // Last character of this sorted rotation
            bwt[i] = src[(rotations[i] + n - 1) % n];
        }
        return new BWTResult(bwt, primaryIndex);
    }

    /**
     * Sorts all cyclic rotations of src by prefix doubling.
     *
     * After the pass for length k, rank[i] orders rotations by their first k bytes.
     * The order by the first 2k bytes is the order of the pairs
     * (rank[i], rank[(i + k) mod n]), which two stable counting sorts produce in O(n).
     * At most log2(n) passes are needed, so the whole sort is O(n log n) even for
     * highly repetitive input, where character-by-character comparison degrades badly.
     *
     * For periodic input some rotations are genuinely identical. They keep tied ranks;
     * any order among them yields the same BWT and decodes to the same block.
     */
    private static int[] sortRotations(byte[] src) {
        int n = src.length;
        int[] sa = new int[n];
        int[] rank = new int[n];
        int[] tmp = new int[n];
        int[] cnt = new int[Math.max(256, n)];

        // Pass 0: counting sort by the first byte.
        for (int i = 0; i < n; i++) cnt[src[i] & 0xFF]++;
        for (int i = 1; i < 256; i++) cnt[i] += cnt[i - 1];
        for (int i = n - 1; i >= 0; i--) sa[--cnt[src[i] & 0xFF]] = i;

        int classes = 1;
        rank[sa[0]] = 0;
        for (int i = 1; i < n; i++) {
            if (src[sa[i]] != src[sa[i - 1]]) classes++;
            rank[sa[i]] = classes - 1;
        }

        for (int k = 1; k < n && classes < n; k <<= 1) {
            // sa is sorted by the first k bytes, so shifting each start back by k
            // gives an order sorted by the second half of the 2k-byte prefix.
            for (int i = 0; i < n; i++) {
                int p = sa[i] - k;
                tmp[i] = p < 0 ? p + n : p;
            }

            // Stable counting sort by the first half's rank.
            java.util.Arrays.fill(cnt, 0, classes, 0);
            for (int i = 0; i < n; i++) cnt[rank[tmp[i]]]++;
            for (int i = 1; i < classes; i++) cnt[i] += cnt[i - 1];
            for (int i = n - 1; i >= 0; i--) sa[--cnt[rank[tmp[i]]]] = tmp[i];

            // Re-rank by the (first, second) pair.
            tmp[sa[0]] = 0;
            classes = 1;
            for (int i = 1; i < n; i++) {
                int a = sa[i], b = sa[i - 1];
                int a2 = a + k; if (a2 >= n) a2 -= n;
                int b2 = b + k; if (b2 >= n) b2 -= n;
                if (rank[a] != rank[b] || rank[a2] != rank[b2]) classes++;
                tmp[a] = classes - 1;
            }
            int[] swap = rank; rank = tmp; tmp = swap;
        }
        return sa;
    }

    // --- Move-To-Front Transform ---

    private static byte[] transformMTF(byte[] input) {
        byte[] list = new byte[256];
        for (int i = 0; i < 256; i++) list[i] = (byte) i;

        byte[] output = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            byte b = input[i];
            int idx = 0;
            while (list[idx] != b) idx++;
            output[i] = (byte) idx;
            System.arraycopy(list, 0, list, 1, idx);
            list[0] = b;
        }
        return output;
    }

    // --- Zero-run coding + Huffman ---

    /** Converts MTF output to the RUNA/RUNB symbol stream and Huffman-codes it. */
    private static byte[] entropyEncode(byte[] mtf) {
        int[] symbols = new int[mtf.length + 1];
        int count = 0;
        int i = 0;
        while (i < mtf.length) {
            int v = mtf[i] & 0xFF;
            if (v == 0) {
                int run = 0;
                while (i < mtf.length && mtf[i] == 0) { run++; i++; }
                // Bijective base-2: digit 1 -> RUNA, digit 2 -> RUNB, least significant first.
                while (run > 0) {
                    if ((run & 1) == 1) { symbols[count++] = RUNA; run = (run - 1) >> 1; }
                    else                { symbols[count++] = RUNB; run = (run - 2) >> 1; }
                }
            } else {
                symbols[count++] = v + 1;
                i++;
            }
        }
        symbols[count++] = EOB;

        int[] freq = new int[ALPHABET];
        for (int s = 0; s < count; s++) freq[symbols[s]]++;
        int[] lengths = huffmanLengths(freq, MAX_CODE_LEN);
        int[] codes = canonicalCodes(lengths);

        BitWriter bw = new BitWriter(mtf.length / 2 + 64);
        for (int s = 0; s < ALPHABET; s++) bw.write(lengths[s], LEN_BITS);
        for (int s = 0; s < count; s++) bw.write(codes[symbols[s]], lengths[symbols[s]]);
        return bw.toByteArray();
    }

    /**
     * Huffman code lengths, limited to maxLen. If the optimal tree is too deep the
     * frequencies are flattened (as bzip2 does) and the tree rebuilt.
     */
    private static int[] huffmanLengths(int[] freq, int maxLen) {
        int[] f = freq.clone();
        while (true) {
            int[] lengths = buildTreeLengths(f);
            int max = 0;
            for (int l : lengths) max = Math.max(max, l);
            if (max <= maxLen) return lengths;
            for (int i = 0; i < f.length; i++) if (f[i] > 0) f[i] = 1 + (f[i] >> 1);
        }
    }

    private static int[] buildTreeLengths(int[] freq) {
        int n = freq.length;
        int[] lengths = new int[n];
        int[] parent = new int[2 * n];
        long[] weight = new long[2 * n];
        PriorityQueue<Integer> pq = new PriorityQueue<>((a, b) ->
                weight[a] != weight[b] ? Long.compare(weight[a], weight[b]) : Integer.compare(a, b));

        int used = 0, lastUsed = -1;
        for (int i = 0; i < n; i++) {
            if (freq[i] > 0) { weight[i] = freq[i]; pq.add(i); used++; lastUsed = i; }
        }
        if (used == 1) { lengths[lastUsed] = 1; return lengths; }

        int next = n;
        while (pq.size() > 1) {
            int a = pq.poll(), b = pq.poll();
            weight[next] = weight[a] + weight[b];
            parent[a] = next;
            parent[b] = next;
            pq.add(next++);
        }
        int root = next - 1;
        for (int i = 0; i < n; i++) {
            if (freq[i] == 0) continue;
            int depth = 0;
            for (int node = i; node != root; node = parent[node]) depth++;
            lengths[i] = depth;
        }
        return lengths;
    }

    /** Assigns canonical codes: shorter codes first, ties broken by symbol order. */
    private static int[] canonicalCodes(int[] lengths) {
        int[] blCount = new int[MAX_CODE_LEN + 1];
        for (int l : lengths) if (l > 0) blCount[l]++;
        int[] nextCode = new int[MAX_CODE_LEN + 1];
        int code = 0;
        for (int len = 1; len <= MAX_CODE_LEN; len++) {
            nextCode[len] = code;
            code = (code + blCount[len]) << 1;
        }
        int[] codes = new int[lengths.length];
        for (int s = 0; s < lengths.length; s++) {
            if (lengths[s] > 0) codes[s] = nextCode[lengths[s]]++;
        }
        return codes;
    }

    // =========================================================================
    // DECOMPRESSION ENGINE
    // =========================================================================

    public static void decompress(File inFile, File outFile) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(inFile)));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {

            byte[] magic = new byte[MAGIC.length];
            try {
                in.readFully(magic);
            } catch (EOFException e) {
                throw new IOException("not a BWTSorter file (too short)");
            }
            if (!java.util.Arrays.equals(magic, MAGIC)) throw new IOException("not a BWTSorter file (bad magic)");
            int version = in.readUnsignedByte();
            if (version != VERSION) throw new IOException("unsupported format version " + version);

            try {
                int blockNo = 0;
                while (true) {
                    int type = in.readUnsignedByte();
                    if (type == BLOCK_END) break;

                    int length = in.readInt();
                    int checksum = in.readInt();
                    if (length <= 0 || length > BLOCK_SIZE) throw new IOException("corrupt block header (length " + length + ")");

                    byte[] original;
                    if (type == BLOCK_STORED) {
                        original = new byte[length];
                        in.readFully(original);
                    } else if (type == BLOCK_BWT) {
                        int primaryIndex = in.readInt();
                        int payloadLen = in.readInt();
                        if (primaryIndex < 0 || primaryIndex >= length || payloadLen <= 0 || payloadLen > 2 * BLOCK_SIZE) {
                            throw new IOException("corrupt block header");
                        }
                        byte[] payload = new byte[payloadLen];
                        in.readFully(payload);

                        byte[] mtf = entropyDecode(payload, length);
                        byte[] bwt = inverseMTF(mtf);
                        original = inverseBWT(bwt, primaryIndex);
                    } else {
                        throw new IOException("unknown block type " + type);
                    }

                    CRC32 crc = new CRC32();
                    crc.update(original);
                    if ((int) crc.getValue() != checksum) {
                        throw new IOException("CRC mismatch in block " + blockNo + " - data is corrupt");
                    }
                    out.write(original);
                    blockNo++;
                }
            } catch (EOFException e) {
                throw new IOException("unexpected end of file - compressed data is truncated");
            }
        }
    }

    // --- Huffman decode + zero-run expansion ---

    private static byte[] entropyDecode(byte[] payload, int targetLength) throws IOException {
        BitReader br = new BitReader(payload);
        int[] lengths = new int[ALPHABET];
        int[] blCount = new int[MAX_CODE_LEN + 1];
        for (int s = 0; s < ALPHABET; s++) {
            lengths[s] = br.read(LEN_BITS);
            if (lengths[s] > MAX_CODE_LEN) throw new IOException("corrupt Huffman table");
            if (lengths[s] > 0) blCount[lengths[s]]++;
        }
        // Symbols sorted by (length, symbol): the order canonical codes are assigned in.
        int[] sorted = new int[ALPHABET];
        int[] offset = new int[MAX_CODE_LEN + 2];
        for (int len = 1; len <= MAX_CODE_LEN; len++) offset[len + 1] = offset[len] + blCount[len];
        for (int s = 0; s < ALPHABET; s++) if (lengths[s] > 0) sorted[offset[lengths[s]]++] = s;

        byte[] mtf = new byte[targetLength];
        int pos = 0;
        int run = 0, weight = 1;

        while (true) {
            int sym = decodeSymbol(br, blCount, sorted);
            if (sym == RUNA || sym == RUNB) {
                run += (sym == RUNA ? 1 : 2) * weight;
                weight <<= 1;
                if (run > targetLength) throw new IOException("corrupt run length");
                continue;
            }
            // Any other symbol ends a pending zero run.
            if (run > 0) {
                if (pos + run > targetLength) throw new IOException("block overflows declared length");
                pos += run;            // array is already zero-filled
                run = 0;
                weight = 1;
            }
            if (sym == EOB) break;
            if (pos >= targetLength) throw new IOException("block overflows declared length");
            mtf[pos++] = (byte) (sym - 1);
        }
        if (pos != targetLength) throw new IOException("block shorter than declared length");
        return mtf;
    }

    /** Canonical Huffman decode, one bit at a time (the zlib "puff" method). */
    private static int decodeSymbol(BitReader br, int[] blCount, int[] sorted) throws IOException {
        int code = 0, first = 0, index = 0;
        for (int len = 1; len <= MAX_CODE_LEN; len++) {
            code |= br.read(1);
            int count = blCount[len];
            if (code - first < count) return sorted[index + code - first];
            index += count;
            first = (first + count) << 1;
            code <<= 1;
        }
        throw new IOException("invalid Huffman code");
    }

    // --- Inverse MTF ---

    private static byte[] inverseMTF(byte[] input) {
        byte[] list = new byte[256];
        for (int i = 0; i < 256; i++) list[i] = (byte) i;

        byte[] output = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            int idx = input[i] & 0xFF;
            byte b = list[idx];
            output[i] = b;
            System.arraycopy(list, 0, list, 1, idx);
            list[0] = b;
        }
        return output;
    }

    // --- Inverse BWT ---

    /**
     * Rebuilds the block in O(n).
     *
     * next[j] is the row whose last-column byte is the same occurrence as the
     * first-column byte of row j (the T-vector, or psi mapping). Row primaryIndex
     * is rotation 0; following next from it visits rotations 1, 2, 3, ... and the
     * last-column byte of rotation r+1 is s[r], so the block comes out front to back.
     */
    private static byte[] inverseBWT(byte[] bwt, int primaryIndex) {
        int n = bwt.length;
        int[] count = new int[257];
        int[] next = new int[n];

        for (int i = 0; i < n; i++) count[(bwt[i] & 0xFF) + 1]++;
        for (int i = 0; i < 256; i++) count[i + 1] += count[i];
        for (int i = 0; i < n; i++) {
            int symbol = bwt[i] & 0xFF;
            next[count[symbol]++] = i;
        }

        byte[] original = new byte[n];
        int curr = primaryIndex;
        for (int i = 0; i < n; i++) {
            curr = next[curr];
            original[i] = bwt[curr];
        }
        return original;
    }

    // =========================================================================
    // BIT I/O (most significant bit first)
    // =========================================================================

    private static class BitWriter {
        private byte[] buf;
        private int pos;
        private long acc;
        private int bits;

        BitWriter(int capacity) { buf = new byte[Math.max(16, capacity)]; }

        void write(int value, int n) {
            acc = (acc << n) | (value & ((1L << n) - 1));
            bits += n;
            while (bits >= 8) {
                bits -= 8;
                put((byte) (acc >>> bits));
            }
        }

        private void put(byte b) {
            if (pos == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
            buf[pos++] = b;
        }

        byte[] toByteArray() {
            if (bits > 0) { put((byte) (acc << (8 - bits))); bits = 0; }
            return java.util.Arrays.copyOf(buf, pos);
        }
    }

    private static class BitReader {
        private final byte[] data;
        private int pos;
        private int acc;
        private int bits;

        BitReader(byte[] data) { this.data = data; }

        int read(int n) throws IOException {
            int result = 0;
            for (int i = 0; i < n; i++) {
                if (bits == 0) {
                    if (pos >= data.length) throw new IOException("Huffman stream ended early");
                    acc = data[pos++] & 0xFF;
                    bits = 8;
                }
                bits--;
                result = (result << 1) | ((acc >>> bits) & 1);
            }
            return result;
        }
    }
}
