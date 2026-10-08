import java.io.*;
import java.util.Arrays;

/**
 * BWTSorter
 *
 * A data compression/decompression utility that treats compression as a sorting problem.
 *
 * COMPRESSION PROCESS:
 * 1. Read input block into memory.
 * 2. Form all lexicographical rotations of the block and SORT them.
 * 3. Take the last column of the sorted rotation matrix (BWT Transform).
 *    Because sorted prefixes group similar characters, the last column ends up
 *    containing long runs of identical bytes.
 * 4. Save the "Primary Index" (the sorting instruction indicating where the original
 *    unrotated string ended up in the sorted array) at the head of the stream.
 * 5. Pass the sorted byte sequence through Move-To-Front (MTF) and Run-Length Encoding (RLE)
 *    to convert byte runs into small run lengths.
 *
 * DECOMPRESSION PROCESS:
 * 1. Read the saved primary index (sorting instruction) and RLE/MTF encoded data.
 * 2. Invert MTF and RLE to recover the BWT transformed array.
 * 3. Reconstruct the original byte array in O(N) time using the LF-mapping algorithm
 *    guided by the primary index.
 */
public class BWTSorter {

    private static final int BLOCK_SIZE = 100_000; // 100 KB block processing size

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
                System.out.println("Compression Complete -> " + outputFile.getName());
            } else if ("-d".equalsIgnoreCase(mode)) {
                System.out.println("Decompressing via Inverted Sorting Mapping...");
                decompress(inputFile, outputFile);
                System.out.println("Decompression Complete -> " + outputFile.getName());
            } else {
                printUsage();
            }
        } catch (IOException e) {
            System.err.println("Error processing file: " + e.getMessage());
            e.printStackTrace();
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

            byte[] buffer = new byte[BLOCK_SIZE];
            int bytesRead;

            while ((bytesRead = in.read(buffer)) > 0) {
                byte[] block = (bytesRead == BLOCK_SIZE) ? buffer : Arrays.copyOf(buffer, bytesRead);

                // Step 1 & 2: Perform Burrows-Wheeler Transform (Sorting-based permutation)
                BWTResult bwt = transformBWT(block);

                // Step 3: Write the "sorting instruction" (original block index)
                out.writeInt(bwt.length);
                out.writeInt(bwt.primaryIndex);

                // Step 4: Move-To-Front (MTF) transform on BWT output
                byte[] mtf = transformMTF(bwt.bwtBlock);

                // Step 5: Run-Length Encode (RLE) and write compressed stream
                writeRLE(mtf, out);
            }
        }
    }

    // --- BWT Transformation (Sorting Phase) ---

    private static class BWTResult {
        final byte[] bwtBlock;
        final int primaryIndex;
        final int length;

        BWTResult(byte[] bwtBlock, int primaryIndex, int length) {
            this.bwtBlock = bwtBlock;
            this.primaryIndex = primaryIndex;
            this.length = length;
        }
    }

    private static BWTResult transformBWT(byte[] src) {
        int n = src.length;
        Integer[] suffixArray = new Integer[n];
        for (int i = 0; i < n; i++) suffixArray[i] = i;

        // Sort all cyclic rotations of the input array lexicographically
        Arrays.sort(suffixArray, (i1, i2) -> {
            for (int k = 0; k < n; k++) {
                int b1 = src[(i1 + k) % n] & 0xFF;
                int b2 = src[(i2 + k) % n] & 0xFF;
                if (b1 != b2) return Integer.compare(b1, b2);
            }
            return 0;
        });

        byte[] bwt = new byte[n];
        int primaryIndex = -1;

        for (int i = 0; i < n; i++) {
            // Find where the original unshifted string ended up
            if (suffixArray[i] == 0) {
                primaryIndex = i;
            }
            // Take the last char of the sorted rotation string
            bwt[i] = src[(suffixArray[i] + n - 1) % n];
        }

        return new BWTResult(bwt, primaryIndex, n);
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

            // Shift elements right and move matched byte to position 0
            System.arraycopy(list, 0, list, 1, idx);
            list[0] = b;
        }
        return output;
    }

    // --- Run-Length Encoding Stream Output ---

    private static void writeRLE(byte[] input, DataOutputStream out) throws IOException {
        int i = 0;
        while (i < input.length) {
            byte runByte = input[i];
            int runLen = 1;
            while (i + runLen < input.length && input[i + runLen] == runByte && runLen < 255) {
                runLen++;
            }
            out.writeByte(runByte);
            out.writeByte(runLen);
            i += runLen;
        }
    }

    // =========================================================================
    // DECOMPRESSION ENGINE
    // =========================================================================

    public static void decompress(File inFile, File outFile) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(inFile)));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {

            while (in.available() > 0) {
                int blockLength = in.readInt();
                int primaryIndex = in.readInt();

                // Step 1: Read RLE data
                byte[] mtf = readRLE(in, blockLength);

                // Step 2: Inverse Move-To-Front
                byte[] bwt = inverseMTF(mtf);

                // Step 3: Inverse BWT using LF-Mapping (Rebuilding original array)
                byte[] original = inverseBWT(bwt, primaryIndex);

                out.write(original);
            }
        }
    }

    // --- RLE Decoding ---

    private static byte[] readRLE(DataInputStream in, int targetLength) throws IOException {
        byte[] output = new byte[targetLength];
        int count = 0;
        while (count < targetLength) {
            byte b = in.readByte();
            int runLen = in.readByte() & 0xFF;
            for (int i = 0; i < runLen; i++) {
                output[count++] = b;
            }
        }
        return output;
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

    // --- Inverse BWT (Using LF-Mapping) ---

    private static byte[] inverseBWT(byte[] bwt, int primaryIndex) {
        int n = bwt.length;
        int[] count = new int[257];
        int[] next = new int[n];

        // 1. Calculate symbol frequencies
        for (int i = 0; i < n; i++) {
            count[(bwt[i] & 0xFF) + 1]++;
        }

        // 2. Compute cumulative counts (First Column offsets)
        for (int i = 0; i < 256; i++) {
            count[i + 1] += count[i];
        }

        // 3. Build LF-Mapping vector (T-vector)
        for (int i = 0; i < n; i++) {
            int symbol = bwt[i] & 0xFF;
            next[count[symbol]] = i;
            count[symbol]++;
        }

        // 4. Walk LF-mapping backwards using the primaryIndex sorting instruction
        byte[] original = new byte[n];
        int curr = primaryIndex;
        for (int i = n - 1; i >= 0; i--) {
            curr = next[curr];
            original[i] = bwt[curr];
        }

        return original;
    }
}
