# BWTSorter

**Make files smaller, then get them back exactly as they were.**

BWTSorter is a small file-compression program, a bit like making a `.zip` file. You give it a file, and it produces a smaller copy. Later you give it the smaller copy, and it gives you back the original, identical down to the last byte.

It works best on text: documents, source code, logs, CSV data and so on. These usually shrink to between a fifth and a twentieth of their original size.

---

## What you need

BWTSorter is written in Java, so you need Java installed on your computer (version 11 or newer).

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
Compression Complete -> myfile.bwts (2689604 -> 534079 bytes)
```

Your original file is left untouched.

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

Some real results:

| Type of file | Original size | Compressed size |
|---|---|---|
| Program source code | 93 KB | 4 KB |
| Written text (a long document) | 2.7 MB | 534 KB |
| Large text collection | 13.5 MB | 2.2 MB |
| Photos, music, videos, `.zip` files | — | about the same size |

That last line is normal. Photos, music, videos and zip files are *already* compressed, so there's nothing left to squeeze. BWTSorter notices this and simply stores those parts as they are, so the file never gets meaningfully bigger.

A 13.5 MB file takes about five seconds to compress and about one second to restore.

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
- **Compressed files from older versions of BWTSorter won't open.** The format changed in version 2.

---

## How does it work, in plain English?

Imagine taking a page of text and writing it out over and over, each copy starting one letter later, until you've started at every letter. Then sort all those copies into alphabetical order, and read down the *last* letter of each one.

That last column has a curious property. Because the copies are sorted by what comes *after* each letter, letters that tend to appear before the same things get grouped together. Every letter that comes just before "he " (often a "t") ends up next to the others. The jumbled text turns into long stretches of repeated letters, and repetition is easy to describe briefly.

Remarkably, the shuffle can be undone exactly, using only that last column and one small number. BWTSorter then uses some standard tricks to store the repetitive result in as few bits as possible.

The idea is called the **Burrows–Wheeler Transform**, published in 1994, and it's the same trick at the heart of the well-known `bzip2` program. If you'd like the full details, see [TECHNICAL.md](TECHNICAL.md).

---

## Author

Mario Gianota
