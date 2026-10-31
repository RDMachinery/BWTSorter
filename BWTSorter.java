import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * BWTSorter
 *
 * A block-sorting compressor written as a single readable file. It treats compression
 * as a sorting problem: sorting all suffixes of a block groups together characters that
 * appear in similar contexts, and a context-mixing model then predicts that grouped
 * stream bit by bit for an arithmetic coder.
 *
 * COMPRESSION PIPELINE (per block, 32 MB by default):
 *  -  x86 filter. If the block looks like x86 machine code, the relative addresses in
 *     CALL and JMP instructions are converted to absolute ones, so that repeated calls
 *     to the same function become identical byte strings.
 *  0. LZP preprocessing. Repeats of 112 bytes or more, predicted from the previous four
 *     bytes, are replaced by a short marker. Used only when it makes the block smaller.
 *  1. Burrows-Wheeler Transform. The suffix array of the block is built with SA-IS
 *     (linear time), and the BWT is read off it. A virtual end-of-block sentinel makes
 *     the rotations unambiguous; its row is stored as the "primary index". Large blocks
 *     let the transform find repeats that are megabytes apart.
 *  2. Context-mixing model. Each bit of the BWT output is predicted by several adaptive
 *     models (order 0, order 1, order 2, and two run models keyed on the previous byte
 *     and how long it has repeated). A gated linear mixer in the logistic domain
 *     combines them, and two adaptive probability maps (APM/SSE) refine the result.
 *  3. Binary arithmetic coding. Each bit is coded with the final probability, so a bit
 *     that the model predicts with 99.9% confidence costs about 0.0014 bits.
 *  4. If the coded block would not be smaller than the raw block (random or
 *     already-compressed data), the block is stored uncompressed instead.
 *
 * FILE FORMAT (version 5; version 3 and 4 files are also read, as they are subsets):
 *   "BWTS" magic, 1 version byte, then a sequence of blocks:
 *     type byte: 0 = end of stream, 1 = BWT-coded, 2 = stored, 3 = LZP + BWT-coded,
 *                4 = x86 + BWT-coded, 5 = x86 + LZP + BWT-coded
 *     int  original length
 *     int  CRC32 of the original bytes
 *     types 3 and 5: int LZP length, then as BWT-coded
 *     BWT-coded: int primary index, int payload length, payload (arithmetic-coded)
 *     stored:    raw bytes
 *
 * DECOMPRESSION runs the same model in lock-step to decode the BWT output, inverts the
 * BWT in O(n) by following the T-vector (psi mapping) from the primary index, expands
 * any LZP markers and reverses the x86 filter. Each block's CRC32 is verified before it
 * is written.
 *
 * CREDITS:
 *   Mario Gianota - architect
 *   Claude (an AI model by Anthropic) - implementation and documentation
 */
public class BWTSorter {

    private static final int DEFAULT_BLOCK_MB = 32;
    private static final int MAX_BLOCK_MB = 256;
    private static final byte[] MAGIC = "BWTS".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 5;
    private static final int OLDEST_READABLE_VERSION = 3;   // versions 3 and 4 are subsets of version 5

    private static final int BLOCK_END = 0;
    private static final int BLOCK_BWT = 1;
    private static final int BLOCK_STORED = 2;
    private static final int BLOCK_BWT_LZP = 3;          // version 4+
    private static final int BLOCK_X86_BWT = 4;          // version 5+
    private static final int BLOCK_X86_BWT_LZP = 5;      // version 5+

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
                int blockMB = DEFAULT_BLOCK_MB;
                if (args.length >= 4) {
                    try {
                        blockMB = Integer.parseInt(args[3]);
                    } catch (NumberFormatException e) {
                        blockMB = -1;
                    }
                    if (blockMB < 1 || blockMB > MAX_BLOCK_MB) {
                        System.err.println("Block size must be a whole number of megabytes from 1 to " + MAX_BLOCK_MB);
                        System.exit(2);
                    }
                }
                System.out.println("Compressing via BWT Block-Sorting...");
                long start = System.nanoTime();
                compress(inputFile, outputFile, blockMB * 1_000_000);
                System.out.printf("Compression Complete -> %s (%d -> %d bytes, %.2fs)%n",
                        outputFile.getName(), inputFile.length(), outputFile.length(),
                        (System.nanoTime() - start) / 1e9);
            } else if ("-d".equalsIgnoreCase(mode)) {
                System.out.println("Decompressing via Inverted Sorting Mapping...");
                long start = System.nanoTime();
                decompress(inputFile, outputFile);
                System.out.printf("Decompression Complete -> %s (%.2fs)%n",
                        outputFile.getName(), (System.nanoTime() - start) / 1e9);
            } else {
                printUsage();
            }
        } catch (IOException e) {
            System.err.println("Error processing file: " + e.getMessage());
            System.exit(1);
        } catch (OutOfMemoryError e) {
            System.err.println("Not enough memory for this block size. Try a smaller block size "
                    + "(e.g. 8) or give Java more memory with: java -Xmx2g BWTSorter ...");
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  Compress:   java BWTSorter -c <input_file> <output_file> [block_MB]");
        System.out.println("  Decompress: java BWTSorter -d <compressed_file> <decompressed_file>");
        System.out.println();
        System.out.println("  block_MB is optional (1-" + MAX_BLOCK_MB + ", default " + DEFAULT_BLOCK_MB
                + "). Larger blocks usually compress better but need more memory.");
    }

    // =========================================================================
    // COMPRESSION ENGINE
    // =========================================================================

    public static void compress(File inFile, File outFile, int blockSize) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(inFile), 1 << 16);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(outFile), 1 << 16))) {

            out.write(MAGIC);
            out.writeByte(VERSION);

            // Don't allocate a 32 MB buffer for a 2 KB file.
            int bufferSize = (int) Math.max(1, Math.min(blockSize, inFile.length()));
            byte[] buffer = new byte[bufferSize];
            int bytesRead;
            while ((bytesRead = in.readNBytes(buffer, 0, buffer.length)) > 0) {
                compressBlock(Arrays.copyOf(buffer, bytesRead), out);
            }
            out.writeByte(BLOCK_END);
        }
    }

    private static void compressBlock(byte[] block, DataOutputStream out) throws IOException {
        int n = block.length;
        CRC32 crc = new CRC32();
        crc.update(block);
        int checksum = (int) crc.getValue();

        // x86 code first, so that LZP can catch the repeats the filter creates.
        boolean x86 = X86.looksLikeX86(block);
        byte[] filtered = block;
        if (x86) {
            filtered = block.clone();
            X86.apply(filtered, true);
        }
        byte[] lzp = LZP.encode(filtered);
        byte[] source = lzp != null ? lzp : filtered;

        BWTResult bwt = transformBWT(source);
        byte[] payload = ArithmeticCoder.encode(bwt.bwtBlock);

        // Extra bytes beyond the common header: primary index + payload length,
        // plus the LZP length when LZP was used.
        int extra = lzp != null ? 12 : 8;
        if ((long) payload.length + extra < n) {
            int type = x86 ? (lzp != null ? BLOCK_X86_BWT_LZP : BLOCK_X86_BWT)
                           : (lzp != null ? BLOCK_BWT_LZP : BLOCK_BWT);
            out.writeByte(type);
            out.writeInt(n);
            out.writeInt(checksum);
            if (lzp != null) out.writeInt(lzp.length);
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

    /**
     * BWT of src with a virtual sentinel '$' that sorts before every byte.
     *
     * The sorted rotations of src$ are the sorted suffixes of src$, so the suffix array
     * gives the row order directly. Row 0 is the suffix "$", preceded by src[n-1]. The row
     * for suffix 0 (the whole block) is preceded by '$' itself; that byte is left out of
     * the output and its row is recorded as the primary index. Output is exactly n bytes.
     */
    private static BWTResult transformBWT(byte[] src) {
        int n = src.length;
        int[] text = new int[n];
        for (int i = 0; i < n; i++) text[i] = src[i] & 0xFF;
        int[] sa = SuffixArray.build(text, 255);
        text = null;

        byte[] bwt = new byte[n];
        bwt[0] = src[n - 1];
        int primaryIndex = -1;
        int j = 1;
        for (int i = 0; i < n; i++) {
            int p = sa[i];
            if (p == 0) primaryIndex = i + 1;
            else bwt[j++] = src[p - 1];
        }
        return new BWTResult(bwt, primaryIndex);
    }

    // =========================================================================
    // DECOMPRESSION ENGINE
    // =========================================================================

    public static void decompress(File inFile, File outFile) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(inFile), 1 << 16));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 1 << 16)) {

            byte[] magic = new byte[MAGIC.length];
            try {
                in.readFully(magic);
            } catch (EOFException e) {
                throw new IOException("not a BWTSorter file (too short)");
            }
            if (!Arrays.equals(magic, MAGIC)) throw new IOException("not a BWTSorter file (bad magic)");
            int version = in.readUnsignedByte();
            if (version < OLDEST_READABLE_VERSION || version > VERSION) {
                throw new IOException("unsupported format version " + version
                        + (version < VERSION ? " (made by an older BWTSorter)" : " (made by a newer BWTSorter)"));
            }

            int maxBlock = MAX_BLOCK_MB * 1_000_000;
            try {
                int blockNo = 0;
                while (true) {
                    int type = in.readUnsignedByte();
                    if (type == BLOCK_END) break;

                    int length = in.readInt();
                    int checksum = in.readInt();
                    if (length <= 0 || length > maxBlock) throw new IOException("corrupt block header (length " + length + ")");

                    byte[] original;
                    if (type == BLOCK_STORED) {
                        original = new byte[length];
                        in.readFully(original);
                    } else if (type == BLOCK_BWT || (type == BLOCK_BWT_LZP && version >= 4)
                            || ((type == BLOCK_X86_BWT || type == BLOCK_X86_BWT_LZP) && version >= 5)) {
                        boolean lzp = type == BLOCK_BWT_LZP || type == BLOCK_X86_BWT_LZP;
                        boolean x86 = type == BLOCK_X86_BWT || type == BLOCK_X86_BWT_LZP;
                        int sortedLen = lzp ? in.readInt() : length;
                        int primaryIndex = in.readInt();
                        int payloadLen = in.readInt();
                        if ((lzp && sortedLen < 4) || sortedLen > length || primaryIndex < 1 || primaryIndex > sortedLen
                                || payloadLen <= 0 || payloadLen >= length) {
                            throw new IOException("corrupt block header");
                        }
                        byte[] payload = new byte[payloadLen];
                        in.readFully(payload);

                        byte[] bwt = ArithmeticCoder.decode(payload, sortedLen);
                        byte[] sorted = inverseBWT(bwt, primaryIndex);
                        original = lzp ? LZP.decode(sorted, length) : sorted;
                        if (x86) X86.apply(original, false);
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

    /**
     * Rebuilds the block in O(n) from the BWT (sentinel removed) and the sentinel's row.
     *
     * Conceptually the last column has n + 1 entries: the stored bytes with '$' inserted
     * at row primaryIndex. next[j] is the row whose last-column byte is the same occurrence
     * as the first-column byte of row j (the T-vector, or psi mapping). Row primaryIndex
     * holds the whole block, so following next from it visits rotations 1, 2, 3, ... and
     * yields the block front to back.
     */
    private static byte[] inverseBWT(byte[] bwt, int primaryIndex) {
        int n = bwt.length;
        int[] start = new int[256];
        for (byte b : bwt) start[b & 0xFF]++;
        int sum = 1;                         // first-column row 0 belongs to '$'
        for (int c = 0; c < 256; c++) {
            int count = start[c];
            start[c] = sum;
            sum += count;
        }

        int[] next = new int[n + 1];
        next[0] = primaryIndex;
        for (int row = 0, i = 0; row <= n; row++) {
            if (row == primaryIndex) continue;
            next[start[bwt[i++] & 0xFF]++] = row;
        }

        byte[] original = new byte[n];
        int curr = primaryIndex;
        for (int k = 0; k < n; k++) {
            curr = next[curr];
            original[k] = bwt[curr < primaryIndex ? curr : curr - 1];
        }
        return original;
    }

    // =========================================================================
    // X86 FILTER
    // =========================================================================

    /**
     * Makes x86 machine code more repetitive before it is sorted.
     *
     * A CALL (opcode E8) or JMP (E9) is followed by a 4-byte little-endian offset relative
     * to the next instruction. Every call to the same function therefore stores a
     * different number, so identical instructions look different. Adding the position
     * turns each offset into the target's absolute address, and calls to the same function
     * become identical byte strings.
     *
     * Only plausible offsets are converted: those whose top byte is 00 or FF (|offset| below
     * 16 MB). The result is kept to the same 25-bit signed range, so its top byte is again
     * 00 or FF and the decoder recognises exactly the same places.
     *
     * After ANY E8/E9 opcode the next four bytes are skipped, converted or not. That keeps
     * the scan identical on both sides: a conversion can never change a byte that the
     * decoder reads to make an earlier decision.
     */
    static final class X86 {
        // Plausible call/jump sites per KB at or above which a block is treated as x86 code.
        // Measured: x86 programs 12-29; blocks only 10-25% x86 code about 3, and the filter
        // still helped them (-2.5% to -8.7%); non-x86 data at most 0.7 (Silesia's Alpha
        // executables, where the filter hurts); random data 0.06.
        static final int MIN_SITES_PER_KB = 2;

        private static boolean opcode(byte b) {
            return b == (byte) 0xE8 || b == (byte) 0xE9;
        }

        private static boolean plausible(byte top) {
            return top == 0 || top == (byte) 0xFF;
        }

        /** True if the block looks like x86 code: enough plausible call/jump sites. */
        static boolean looksLikeX86(byte[] b) {
            int n = b.length;
            if (n < 4096) return false;
            long sites = 0;
            for (int i = 0; i + 4 < n; ) {
                if (opcode(b[i])) {
                    if (plausible(b[i + 4])) sites++;
                    i += 5;
                } else {
                    i++;
                }
            }
            return sites * 1024 >= (long) MIN_SITES_PER_KB * n;
        }

        /** Relative offsets to absolute addresses (encode) or back (decode), in place. */
        static void apply(byte[] b, boolean encode) {
            int n = b.length;
            for (int i = 0; i + 4 < n; ) {
                if (!opcode(b[i])) {
                    i++;
                    continue;
                }
                if (plausible(b[i + 4])) {
                    int v = (b[i + 1] & 0xFF) | (b[i + 2] & 0xFF) << 8 | (b[i + 3] & 0xFF) << 16 | (b[i + 4] & 0xFF) << 24;
                    int pos = i + 5;
                    v = encode ? v + pos : v - pos;
                    v = (v << 7) >> 7;                        // sign-extend from 25 bits
                    b[i + 1] = (byte) v;
                    b[i + 2] = (byte) (v >>> 8);
                    b[i + 3] = (byte) (v >>> 16);
                    b[i + 4] = (byte) (v >>> 24);
                }
                i += 5;
            }
        }
    }

    // =========================================================================
    // LZP PREPROCESSING
    // =========================================================================

    /**
     * Lempel-Ziv Prediction: removes long repeated strings before the BWT.
     *
     * At each position the previous 4 bytes are hashed into a table that remembers where
     * that context was last seen. That position is the prediction: if the text there
     * matches the text here for at least MIN_MATCH bytes, the whole repeat is replaced by
     * a MATCH marker and its length. No offset is stored, because the decoder sees the
     * same context and makes the same prediction.
     *
     * Why it helps a BWT: a passage repeated k times puts k copies of every one of its
     * contexts side by side after sorting. Each copy is cheap, but not free, and the
     * places where the copies start and end cost real bits. Removing the repeats outright
     * is cheaper. Short repeats are left alone, because the BWT and the model already
     * handle them better than a marker would.
     *
     * Encoding:
     *   literal byte b             -> b
     *   literal MATCH, predicted   -> MATCH, 255   (escaped only when a prediction exists,
     *                                               since only then can the decoder misread it)
     *   repeat of length L         -> MATCH, then L - MIN_MATCH as bytes of 254 followed by
     *                                 a final byte 0..253
     * Positions inside a repeat are not added to the table; the decoder skips them too.
     */
    static final class LZP {
        static final int MATCH = 0xF2;
        // Tuned on the Silesia corpus: 32 hurt and 40 (bzip3's choice) barely helped, because
        // the model already handles medium repeats better than a marker; 96-160 were best.
        static final int MIN_MATCH = 112;
        static final int HASH_BITS = 20;                 // 4 MB table; 2^22 gained 0.006%

        private static int hash(int ctx) {
            return (ctx * 0x9E3779B1) >>> (32 - HASH_BITS);
        }

        private static int context(byte[] b, int end) {
            return ((b[end - 4] & 0xFF) << 24) | ((b[end - 3] & 0xFF) << 16)
                    | ((b[end - 2] & 0xFF) << 8) | (b[end - 1] & 0xFF);
        }

        /** The LZP form of in, or null if it would not be smaller. */
        static byte[] encode(byte[] in) {
            int n = in.length;
            if (n < MIN_MATCH + 8) return null;
            byte[] out = new byte[n];
            int[] table = new int[1 << HASH_BITS];      // 0 = context not seen yet
            int limit = n - 4;                          // room for the largest single step
            System.arraycopy(in, 0, out, 0, 4);
            int i = 4, o = 4;
            int ctx = context(in, 4);

            while (i < n) {
                if (o >= limit) return null;
                int h = hash(ctx);
                int ref = table[h];
                table[h] = i;
                // A repeat long enough to use must agree at its last required byte, so test
                // that one first: most candidates fail there without a byte-by-byte scan.
                if (ref > 0 && i + MIN_MATCH <= n && in[ref + MIN_MATCH - 1] == in[i + MIN_MATCH - 1]) {
                    int len = 0, max = n - i;
                    while (len < max && in[ref + len] == in[i + len]) len++;
                    if (len >= MIN_MATCH) {
                        out[o++] = (byte) MATCH;
                        int v = len - MIN_MATCH;
                        while (v >= 254) {
                            if (o >= limit) return null;
                            out[o++] = (byte) 254;
                            v -= 254;
                        }
                        out[o++] = (byte) v;
                        i += len;
                        ctx = context(in, i);
                        continue;
                    }
                }
                int b = in[i++] & 0xFF;
                out[o++] = (byte) b;
                if (b == MATCH && ref > 0) out[o++] = (byte) 255;
                ctx = (ctx << 8) | b;
            }
            return Arrays.copyOf(out, o);
        }

        /** Expands an LZP stream back to exactly length bytes. */
        static byte[] decode(byte[] in, int length) throws IOException {
            if (in.length < 4 || length < 4) throw new IOException("corrupt LZP stream");
            byte[] out = new byte[length];
            int[] table = new int[1 << HASH_BITS];
            System.arraycopy(in, 0, out, 0, 4);
            int p = 4, o = 4;
            int ctx = context(out, 4);

            while (o < length) {
                if (p >= in.length) throw new IOException("LZP stream ended early");
                int h = hash(ctx);
                int ref = table[h];
                table[h] = o;
                int b = in[p++] & 0xFF;
                if (b == MATCH && ref > 0) {
                    if (p >= in.length) throw new IOException("LZP stream ended early");
                    int c = in[p++] & 0xFF;
                    if (c != 255) {
                        long len = MIN_MATCH;
                        while (true) {
                            len += c;
                            if (c != 254) break;
                            if (p >= in.length) throw new IOException("LZP stream ended early");
                            c = in[p++] & 0xFF;
                        }
                        if (o + len > length) throw new IOException("LZP repeat overruns the block");
                        // Byte by byte: the source may overlap what is being written.
                        for (int k = 0; k < len; k++) out[o + k] = out[ref + k];
                        o += (int) len;
                        ctx = context(out, o);
                        continue;
                    }
                }
                out[o++] = (byte) b;
                ctx = (ctx << 8) | b;
            }
            if (p != in.length) throw new IOException("LZP stream has trailing data");
            return out;
        }
    }

    // =========================================================================
    // SUFFIX ARRAY CONSTRUCTION (SA-IS)
    // =========================================================================

    /**
     * SA-IS: suffix array construction in O(n) time (Nong, Zhang and Chan, 2009).
     *
     * Suffixes are classified as S-type (smaller than the next suffix) or L-type (larger).
     * The leftmost S-type positions (LMS) split the text into substrings. Placing the LMS
     * suffixes at the ends of their buckets and "inducing" L-types left to right and then
     * S-types right to left sorts the LMS substrings. If they aren't all distinct, the
     * problem is reduced to a string of their ranks (at most half as long) and solved
     * recursively; a final induce from the correctly ordered LMS suffixes sorts everything.
     *
     * Structure follows the AtCoder Library implementation.
     */
    static final class SuffixArray {

        static int[] build(int[] s, int upper) {
            int n = s.length;
            if (n == 0) return new int[0];
            if (n == 1) return new int[]{0};
            if (n == 2) return s[0] < s[1] ? new int[]{0, 1} : new int[]{1, 0};

            int[] sa = new int[n];
            boolean[] ls = new boolean[n];     // true = S-type
            for (int i = n - 2; i >= 0; i--) {
                ls[i] = (s[i] == s[i + 1]) ? ls[i + 1] : (s[i] < s[i + 1]);
            }

            int[] sumL = new int[upper + 1];
            int[] sumS = new int[upper + 1];
            for (int i = 0; i < n; i++) {
                if (!ls[i]) sumS[s[i]]++;
                else sumL[s[i] + 1]++;
            }
            for (int i = 0; i <= upper; i++) {
                sumS[i] += sumL[i];
                if (i < upper) sumL[i + 1] += sumS[i];
            }

            int[] lmsMap = new int[n + 1];
            Arrays.fill(lmsMap, -1);
            int m = 0;
            for (int i = 1; i < n; i++) {
                if (!ls[i - 1] && ls[i]) lmsMap[i] = m++;
            }
            int[] lms = new int[m];
            for (int i = 1, k = 0; i < n; i++) {
                if (!ls[i - 1] && ls[i]) lms[k++] = i;
            }

            int[] buf = new int[upper + 1];
            induce(s, sa, ls, sumL, sumS, buf, lms, m);

            if (m > 0) {
                int[] sortedLms = new int[m];
                for (int i = 0, k = 0; i < n; i++) {
                    int v = sa[i];
                    if (lmsMap[v] != -1) sortedLms[k++] = v;
                }

                int[] recS = new int[m];
                int recUpper = 0;
                recS[lmsMap[sortedLms[0]]] = 0;
                for (int i = 1; i < m; i++) {
                    int l = sortedLms[i - 1], r = sortedLms[i];
                    int endL = (lmsMap[l] + 1 < m) ? lms[lmsMap[l] + 1] : n;
                    int endR = (lmsMap[r] + 1 < m) ? lms[lmsMap[r] + 1] : n;
                    boolean same = true;
                    if (endL - l != endR - r) {
                        same = false;
                    } else {
                        while (l < endL) {
                            if (s[l] != s[r]) break;
                            l++;
                            r++;
                        }
                        if (l == n || s[l] != s[r]) same = false;
                    }
                    if (!same) recUpper++;
                    recS[lmsMap[sortedLms[i]]] = recUpper;
                }
                lmsMap = null;

                int[] recSa = build(recS, recUpper);
                for (int i = 0; i < m; i++) sortedLms[i] = lms[recSa[i]];
                induce(s, sa, ls, sumL, sumS, buf, sortedLms, m);
            }
            return sa;
        }

        private static void induce(int[] s, int[] sa, boolean[] ls, int[] sumL, int[] sumS,
                                   int[] buf, int[] lms, int m) {
            int n = s.length;
            Arrays.fill(sa, -1);
            System.arraycopy(sumS, 0, buf, 0, buf.length);
            for (int i = 0; i < m; i++) {
                int d = lms[i];
                if (d == n) continue;
                sa[buf[s[d]]++] = d;
            }
            System.arraycopy(sumL, 0, buf, 0, buf.length);
            sa[buf[s[n - 1]]++] = n - 1;
            for (int i = 0; i < n; i++) {
                int v = sa[i];
                if (v >= 1 && !ls[v - 1]) sa[buf[s[v - 1]]++] = v - 1;
            }
            System.arraycopy(sumL, 0, buf, 0, buf.length);
            for (int i = n - 1; i >= 0; i--) {
                int v = sa[i];
                // An S-type suffix is smaller than some later character, so it never
                // starts with the largest symbol and s[v - 1] + 1 stays in range.
                if (v >= 1 && ls[v - 1]) sa[--buf[s[v - 1] + 1]] = v - 1;
            }
        }
    }

    // =========================================================================
    // CONTEXT-MIXING MODEL
    // =========================================================================

    /** Shared logistic-domain helpers (integer, so encoder and decoder agree exactly). */
    static final class Logistic {
        static final int[] STRETCH = new int[4096];
        private static final int[] SQUASH_POINTS = {
                1, 2, 3, 6, 10, 16, 27, 45, 73, 120, 194, 310, 488, 747, 1101, 1546,
                2047, 2549, 2994, 3348, 3607, 3785, 3901, 3975, 4022, 4050, 4068, 4079,
                4085, 4089, 4092, 4093, 4094};

        static {
            int pi = 0;
            for (int x = -2047; x <= 2047; x++) {
                int v = squash(x);
                for (int i = pi; i <= v; i++) STRETCH[i] = x;
                pi = v + 1;
            }
            for (int i = pi; i < 4096; i++) STRETCH[i] = 2047;
        }

        /** 4096 / (1 + e^(-d/256)), interpolated from 33 points; d in [-2047, 2047]. */
        static int squash(int d) {
            if (d > 2047) return 4095;
            if (d < -2047) return 1;
            int w = d & 127;
            int i = (d >> 7) + 16;
            return (SQUASH_POINTS[i] * (128 - w) + SQUASH_POINTS[i + 1] * w + 64) >> 7;
        }

        static int stretch(int p) {
            return STRETCH[p];
        }
    }

    /**
     * Adaptive probability for each context, stored as a 22-bit probability and a 10-bit
     * hit count in one int. The update step is 1/(count + 1.5) until the count reaches
     * the limit, so new contexts learn fast and settled ones become stable but can still
     * drift as the data changes.
     */
    static final class StateMap {
        private static final int[] DT = new int[1024];
        static {
            for (int i = 0; i < 1024; i++) DT[i] = 16384 / (i + i + 3);
        }

        final int[] t;
        private final int limit;

        StateMap(int size, int limit) {
            t = new int[size];
            Arrays.fill(t, 1 << 31);
            this.limit = limit;
        }

        /** 12-bit probability that the next bit is 1. */
        int p(int cx) {
            return t[cx] >>> 20;
        }

        void update(int cx, int y) {
            int v = t[cx];
            int n = v & 1023;
            int p = v >>> 10;
            if (n < limit) v++;
            else v = (v & 0xfffffc00) | limit;
            v += ((((y << 22) - p) >> 3) * DT[n]) & 0xfffffc00;
            t[cx] = v;
        }
    }

    /**
     * Adaptive probability map (secondary estimation): refines an input probability in a
     * given context by interpolating between 33 learned points along the stretch axis.
     */
    static final class APM {
        private final int[] t;
        private final int rate;
        private int index;

        APM(int contexts, int rate) {
            t = new int[contexts * 33];
            this.rate = rate;
            for (int i = 0; i < contexts; i++) {
                for (int j = 0; j < 33; j++) {
                    t[i * 33 + j] = Logistic.squash((j - 16) * 128) * 16;
                }
            }
        }

        int p(int pr, int cx) {
            int s = Logistic.stretch(pr) + 2048;
            int lo = s >> 7, w = s & 127;
            int base = cx * 33 + lo;
            index = base + (w >> 6);
            return (t[base] * (128 - w) + t[base + 1] * w) >> 11;
        }

        void update(int y) {
            int g = (y << 16) + (y << rate) - y - y;
            t[index] += (g - t[index]) >> rate;
        }
    }

    /**
     * Predicts the BWT output one bit at a time (most significant bit first).
     *
     * Inputs to the mixer, each a stretched probability:
     *   order 0   - the bits of the current byte seen so far
     *   order 1   - plus the previous byte
     *   order 2   - plus the two previous bytes
     *   run       - previous byte and how many times it has repeated (BWT output is
     *               dominated by runs, and run length strongly predicts continuation)
     *   run match - "will this byte repeat the previous one?": the bit the previous byte
     *               predicts, while the partial byte still agrees with it, by run length
     *   bias      - a constant input the mixer can weight freely
     * Weights are selected by run length and the partial byte, then two APM stages refine
     * the mixed probability using order-1 and run contexts.
     *
     * The adaptation limits were tuned on the Silesia corpus. BWT output changes character
     * every few hundred bytes as the sort moves from one context to the next, so the
     * order-n maps forget quickly (limits of 8-15), while the run maps, whose statistics
     * hold across the whole block, are allowed to settle.
     */
    static final class Predictor {
        private static final int INPUTS = 6;

        private final StateMap o0 = new StateMap(256, 15);
        private final StateMap o1 = new StateMap(1 << 16, 12);
        private final StateMap o2 = new StateMap(1 << 24, 8);
        private final StateMap runMap = new StateMap(256 * 16 * 256, 127);
        private final StateMap runMatch = new StateMap(16 * 2 * 256, 255);
        private final APM apm1 = new APM(1 << 16, 7);
        private final APM apm2 = new APM(16 * 256, 7);

        private final int[] weights;
        private final int[] st = new int[INPUTS];
        private int weightBase;

        private int c0 = 1, c1, c2, run;
        private int runBucket;
        private int cx1, cx2, cxRun, cxMatch;
        private int pMix, pr = 2048;

        Predictor() {
            weights = new int[16 * 256 * INPUTS];
            Arrays.fill(weights, (1 << 16) / 3);
            setContexts();
        }

        /** 12-bit probability that the next bit is 1. */
        int p() {
            return pr;
        }

        void update(int y) {
            // Train every component on the actual bit.
            o0.update(c0, y);
            o1.update(cx1, y);
            o2.update(cx2, y);
            runMap.update(cxRun, y);
            runMatch.update(cxMatch, y);

            int err = ((y << 12) - pMix) * 16;
            for (int i = 0; i < INPUTS; i++) {
                weights[weightBase + i] += (st[i] * err) >> 16;
            }
            apm1.update(y);
            apm2.update(y);

            // Advance the bit/byte state.
            c0 = (c0 << 1) | y;
            if (c0 >= 256) {
                int b = c0 & 255;
                if (b == c1) run++;
                else run = 0;
                c2 = c1;
                c1 = b;
                c0 = 1;
                runBucket = run < 8 ? run : run < 16 ? 8 + ((run - 8) >> 1) : run < 64 ? 12 + ((run - 16) >> 4) : 15;
            }
            setContexts();
        }

        private void setContexts() {
            cx1 = (c1 << 8) | c0;
            cx2 = (c2 << 16) | (c1 << 8) | c0;
            cxRun = (((c1 << 4) | runBucket) << 8) | c0;

            // Does the partial byte still agree with the previous byte's leading bits?
            int bitsSoFar = 31 - Integer.numberOfLeadingZeros(c0);
            boolean onTrack = (c0 ^ (0x100 | c1) >>> (8 - bitsSoFar)) == 0;
            int expectedBit = onTrack ? (c1 >>> (7 - bitsSoFar)) & 1 : 0;
            cxMatch = (((runBucket << 1) | expectedBit) << 8) | (onTrack ? c0 : 0);

            st[0] = Logistic.stretch(o0.p(c0));
            st[1] = Logistic.stretch(o1.p(cx1));
            st[2] = Logistic.stretch(o2.p(cx2));
            st[3] = Logistic.stretch(runMap.p(cxRun));
            st[4] = Logistic.stretch(runMatch.p(cxMatch));
            st[5] = 256;

            weightBase = ((runBucket << 8) | c0) * INPUTS;
            long dot = 0;
            for (int i = 0; i < INPUTS; i++) dot += (long) st[i] * weights[weightBase + i];
            int mixed = (int) Math.max(-2047, Math.min(2047, dot >> 16));
            pMix = Logistic.squash(mixed);

            int a1 = apm1.p(pMix, cx1);
            int a2 = apm2.p(pMix, (runBucket << 8) | c0);
            int p = (pMix + a1 + a2 * 2 + 2) >> 2;
            pr = Math.max(1, Math.min(4095, p));
        }
    }

    // =========================================================================
    // BINARY ARITHMETIC CODER
    // =========================================================================

    /**
     * Carry-less binary arithmetic coder with a 32-bit range [x1, x2]. Each bit splits the
     * range in proportion to its predicted probability; whenever the top byte of x1 and x2
     * agrees it can never change again, so it is shifted out.
     */
    static final class ArithmeticCoder {

        static byte[] encode(byte[] data) {
            Predictor model = new Predictor();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 3));
            long x1 = 0, x2 = 0xFFFFFFFFL;

            for (byte value : data) {
                int c = value & 0xFF;
                for (int bit = 7; bit >= 0; bit--) {
                    int y = (c >>> bit) & 1;
                    long xmid = x1 + ((x2 - x1) >>> 12) * model.p();
                    if (y == 1) x2 = xmid;
                    else x1 = xmid + 1;
                    model.update(y);
                    while (((x1 ^ x2) & 0xFF000000L) == 0) {
                        out.write((int) (x2 >>> 24));
                        x1 = (x1 << 8) & 0xFFFFFFFFL;
                        x2 = ((x2 << 8) & 0xFFFFFFFFL) | 0xFF;
                    }
                }
            }
            // Flush enough of x1 to identify a point inside the final range.
            for (int i = 0; i < 4; i++) {
                out.write((int) (x1 >>> 24));
                x1 = (x1 << 8) & 0xFFFFFFFFL;
            }
            return out.toByteArray();
        }

        static byte[] decode(byte[] payload, int length) {
            Predictor model = new Predictor();
            byte[] data = new byte[length];
            long x1 = 0, x2 = 0xFFFFFFFFL, x = 0;
            int pos = 0;
            for (int i = 0; i < 4; i++) {
                x = (x << 8) | (pos < payload.length ? payload[pos++] & 0xFF : 0);
            }

            for (int k = 0; k < length; k++) {
                int c = 0;
                for (int bit = 0; bit < 8; bit++) {
                    long xmid = x1 + ((x2 - x1) >>> 12) * model.p();
                    int y;
                    if (x <= xmid) {
                        y = 1;
                        x2 = xmid;
                    } else {
                        y = 0;
                        x1 = xmid + 1;
                    }
                    model.update(y);
                    c = (c << 1) | y;
                    while (((x1 ^ x2) & 0xFF000000L) == 0) {
                        x1 = (x1 << 8) & 0xFFFFFFFFL;
                        x2 = ((x2 << 8) & 0xFFFFFFFFL) | 0xFF;
                        x = ((x << 8) & 0xFFFFFFFFL) | (pos < payload.length ? payload[pos++] & 0xFF : 0);
                    }
                }
                data[k] = (byte) c;
            }
            return data;
        }
    }
}
