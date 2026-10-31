# BWTSorter

**Make files smaller, then get them back exactly as they were.**

BWTSorter is a small file-compression program, a bit like making a `.zip` file. You give it a file, and it produces a smaller copy. Later you give it the smaller copy, and it gives you back the original, identical down to the last byte.

It works best on text: documents, source code, logs, CSV data, databases and so on. These usually shrink to between a fifth and a twentieth of their original size, often smaller than the well-known `xz` and `bzip2` tools manage.

---

## What you need

BWTSorter is written in Java, so you need Java installed on your computer (version 11 or newer). Any computer with 4 GB of memory or more is fine.

To check whether you already have it, open **Terminal** (on a Mac: press `Cmd + Space`, type *Terminal*, press Enter) and type:

```
java -version
```

If you see a version number of 11 or higher, you're ready. If you get "command not found", download a free copy of Java from [adoptium.net](https://adoptium.net), install it, and try again.

---

## Getting started

1. Put `BWTSorter.java` in a folder, for example a folder called `BWTSorter` in your home folder.
2. Open Terminal and go to that folder:
   ```
   cd ~/BWTSorter
   ```
3. Build the program (you only need to do this once):
   ```
   javac BWTSorter.java
   ```
   This creates a file called `BWTSorter.class` next to the original. No message means it worked.

---

## Shrinking a file (compressing)

```
java BWTSorter -c myfile.txt myfile.bwts
```

- `-c` means **compress**.
- `myfile.txt` is the file you want to shrink.
- `myfile.bwts` is the name of the smaller file to create. You can call it anything; `.bwts` is just a handy reminder of what it is.

When it finishes, it tells you the before and after sizes:

```
Compressing via BWT Block-Sorting...
Compression Complete -> myfile.bwts (10192446 -> 2246292 bytes, 6.93s)
```

Your original file is left untouched.

### Optional: block size

BWTSorter works through your file in pieces called *blocks*, 32 MB at a time by default. Bigger blocks let it spot repetition across more of the file, which usually means a smaller result, but they need more memory. You can choose the block size, in megabytes, by adding a number at the end:

```
java BWTSorter -c myfile.txt myfile.bwts 8
```

You don't need to remember the block size to decompress; the compressed file records everything it needs. If your computer runs short of memory, BWTSorter will say so and suggest a smaller block size.

## Getting the file back (decompressing)

```
java BWTSorter -d myfile.bwts myfile-restored.txt
```

- `-d` means **decompress**.
- `myfile.bwts` is the compressed file.
- `myfile-restored.txt` is the name to give the restored file.

> ⚠️ If a file with the output name already exists, it will be **replaced** without asking. Pick a new name if you want to keep both.

---

## How well does it work?

To find out, BWTSorter was tested against the most popular compression programs on the **Silesia corpus**, a standard collection of 12 test files (212 MB in total) that compression programs are measured with. Every program was set to squeeze as hard as it can.

### Overall: everything squeezed together

| Program | Where you might have met it | All 12 files compressed to |
|---|---|---|
| **BWTSorter** | this program | **46.3 MB** 🏆 |
| bzip3 | the newer successor to bzip2, BWTSorter's closest relative | 46.4 MB |
| bsc | specialist tool from the same family | 46.8 MB |
| 7-Zip (PPMd mode) | the 7-Zip app, "PPMd" option | 47.7 MB |
| xz | `.tar.xz` downloads on Mac and Linux | 48.5 MB |
| 7-Zip (normal mode) | `.7z` files | 48.8 MB |
| brotli | how many websites send pages to your browser | 49.6 MB |
| zstd | Facebook's compressor, used inside many apps and systems | 52.4 MB |
| bzip2 | `.bz2` / `.tar.bz2` files | 54.5 MB |
| gzip / zip | `.gz` and ordinary `.zip` files | 67.6 MB |
| lz4 | games and databases, where speed matters most | 77.4 MB |

**BWTSorter came first**, a whisker ahead of its close relatives bzip3 and bsc, and comfortably ahead of everything most people use day to day. The same files take up about **30% less space** than as ordinary `.zip` files.

### File by file

| Type of file | Original | BWTSorter | 7-Zip | xz | zip |
|---|---|---|---|---|---|
| Novels by Charles Dickens | 10.2 MB | **2.25 MB** | 2.83 MB | 2.83 MB | 3.85 MB |
| An English dictionary | 41.5 MB | **6.63 MB** | 8.39 MB | 8.37 MB | 12.06 MB |
| A database | 10.1 MB | **2.19 MB** | 2.85 MB | 2.84 MB | 3.72 MB |
| Medical scan (MRI) | 10.0 MB | **2.11 MB** | 2.75 MB | 2.75 MB | 3.67 MB |
| XML documents | 5.3 MB | **0.39 MB** | 0.46 MB | 0.43 MB | 0.66 MB |
| Program source code | 21.6 MB | 3.86 MB | 3.76 MB | **3.74 MB** | 5.41 MB |
| Program files (Firefox) | 51.2 MB | 15.8 MB | **13.3 MB** | 13.4 MB | 19.0 MB |

(7-Zip is shown in its normal mode. The "zip" column is gzip, which uses the same method as `.zip` files.)

BWTSorter is especially good with text, data and images. It's less good with program files (apps and libraries), where 7-Zip and xz do better.

Photos, music, videos and `.zip` files will come out about the same size, which is normal. These are *already* compressed, so there's nothing left to squeeze.

### The catch: speed

BWTSorter handles about 1.4 MB per second, both when compressing and when restoring. A 10 MB document takes about 7 seconds each way, and the whole 212 MB test collection took about 2½ minutes each way.

That's similar to other programs at their strongest settings when compressing (faster than xz and brotli, in fact), but much slower when restoring: programs like zip, xz and 7-Zip unpack files in a second or two. BWTSorter gets its extra squeeze by doing careful prediction work in both directions. That makes it best suited to archiving, where you compress something once and rarely need it back, rather than files you open every day.

---

## Is my data safe?

Yes. Every piece of the file carries a checksum, a kind of fingerprint. When you decompress, BWTSorter checks each piece against its fingerprint before writing it. If the compressed file has been damaged or cut short, for example by a failed download, it stops and tells you rather than handing you a quietly broken file:

```
Error processing file: CRC mismatch in block 2 - data is corrupt
```

If you try to decompress something that wasn't made by BWTSorter, it says so:

```
Error processing file: not a BWTSorter file (bad magic)
```

---

## Things to know

- **It works on one file at a time.** To compress a whole folder, first put it in a single file (for example with your Mac's *Compress* option, or `tar`), then compress that.
- **Compressed files can only be opened with BWTSorter.** Other programs such as zip tools won't recognise the `.bwts` format.
- **Older compressed files.** This version (4) opens files made by version 3. Files made by versions 1 and 2 won't open; decompress them with the version that made them.

---

## How does it work, in plain English?

First, BWTSorter looks for long passages that repeat word for word, at least 112 characters long, and replaces each repeat with a short note that says "same again". The rest of the work then has less to get through.

Next, imagine taking a page of text and writing it out over and over, each copy starting one letter later, until you've started at every letter. Then sort all those copies into alphabetical order, and read down the *last* letter of each one.

That last column has a curious property. Because the copies are sorted by what comes *after* each letter, letters that tend to appear before the same things get grouped together. Every letter that comes just before "he " (often a "t") ends up next to the others. The jumbled text turns into long stretches of repeated letters, and repetition is easy to describe briefly.

Remarkably, the shuffle can be undone exactly, using only that last column and one small number.

BWTSorter then reads through the shuffled text making predictions, a bit like a weather forecaster: "after a run of `t`s, the next letter is very probably another `t`." Each prediction comes from several small forecasters whose opinions are blended, with more weight given to whichever has been right lately. When a prediction is confident and correct, the letter costs almost nothing to store; only the surprises take up real space.

The idea is called the **Burrows–Wheeler Transform**, published in 1994, and it's the same trick at the heart of the well-known `bzip2` program. The forecasting approach, known as *context mixing*, is a more recent development. If you'd like the full details, see [TECHNICAL.md](TECHNICAL.md).

---

## Credits

- **Mario Gianota** — architect
- **Claude** (an AI model by Anthropic) — implementation and documentation
