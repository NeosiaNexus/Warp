/*
 * Copyright (C) 2026 Warp Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.warp.proxy.antibot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The trusted identities on disk, without a database: a snapshot of the table, {@code
 * verified.dat}, and an append-only journal of what changed since, {@code verified.journal}, both
 * written by the store's thread only.
 *
 * <ul>
 *   <li>Snapshot: an 8-byte magic {@code WARPVS01}, the entry count, then 32 bytes per entry (key,
 *       verified-at and last-seen minutes, method, padding), then a CRC32C of all that. Written to
 *       a temporary file, forced to disk, then moved over the old one atomically: a crash leaves
 *       the old snapshot or the new one, never half of one.
 *   <li>Journal: 32 bytes per change (operation, method, minutes, key) ending in a CRC32C of the
 *       first 28. Appended in memory, written and forced every second, so a crash loses at most a
 *       second of changes; a torn last entry fails its checksum and is cut off at the next start.
 * </ul>
 *
 * <p>Loading replays the journal over the snapshot. Every change is idempotent (an upsert or a
 * removal), so replaying a change the snapshot already holds changes nothing. A snapshot that fails
 * its checksum is set aside ({@code verified.dat.damaged}) and the journal alone is replayed.
 */
final class VerifiedFiles implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(VerifiedFiles.class);

  static final String SNAPSHOT = "verified.dat";
  static final String JOURNAL = "verified.journal";

  /** {@code WARPVS01} in ASCII. */
  private static final long MAGIC = 0x5741_5250_5653_3031L;

  static final int ENTRY = 32;
  private static final int HEADER = 16;
  private static final byte PUT = 1;
  private static final byte REMOVE = 2;

  /** Journal entries buffered before a write, whatever the time. */
  private static final int BUFFERED_ENTRIES = 2048;

  private final Path directory;
  private final Path snapshot;
  private final Path journal;
  private final ByteBuffer pending = ByteBuffer.allocate(BUFFERED_ENTRIES * ENTRY);
  private final FileChannel journalChannel;
  private long journalEntries;

  /** What loading found. */
  record Loaded(long snapshotEntries, long journalEntries, boolean damaged) {}

  /**
   * Opens the files of a directory, creating it.
   *
   * @param directory where the files live
   * @throws IOException if the directory or the journal cannot be opened
   */
  VerifiedFiles(Path directory) throws IOException {
    this.directory = directory;
    this.snapshot = directory.resolve(SNAPSHOT);
    this.journal = directory.resolve(JOURNAL);
    Files.createDirectories(directory);
    this.journalChannel = openJournal();
  }

  private FileChannel openJournal() throws IOException {
    return FileChannel.open(
        journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
  }

  // ---------------------------------------------------------------------------
  // Loading
  // ---------------------------------------------------------------------------

  /**
   * Loads the snapshot, then replays the journal, into {@code table}, skipping identities last seen
   * before {@code cutoff}, and leaves the journal positioned after its last valid entry.
   *
   * @param table an empty table
   * @param cutoff the first minute still valid
   * @return what was found
   * @throws IOException if a file cannot be read
   */
  Loaded load(VerifiedTable table, int cutoff) throws IOException {
    long fromSnapshot = 0;
    boolean damaged = false;
    try {
      byte[] bytes = Files.readAllBytes(snapshot);
      long count = readSnapshot(bytes, table, cutoff);
      if (count < 0) {
        damaged = true;
        Path aside = directory.resolve(SNAPSHOT + ".damaged");
        Files.move(snapshot, aside, StandardCopyOption.REPLACE_EXISTING);
        logger.warn(
            "{} fails its checksum: set aside as {}; replaying the journal alone", snapshot, aside);
      } else {
        fromSnapshot = count;
      }
    } catch (NoSuchFileException e) {
      // First start, or never snapshotted: the journal alone.
    }
    long replayed = replayJournal(table, cutoff);
    this.journalEntries = replayed;
    return new Loaded(fromSnapshot, replayed, damaged);
  }

  /** Reads a snapshot into the table; returns its entry count, or -1 if it is damaged. */
  private static long readSnapshot(byte[] bytes, VerifiedTable table, int cutoff) {
    if (bytes.length < HEADER + 8) {
      return -1;
    }
    ByteBuffer buf = ByteBuffer.wrap(bytes);
    if (buf.getLong() != MAGIC) {
      return -1;
    }
    long count = buf.getLong();
    if (count < 0 || HEADER + count * ENTRY + 8 != bytes.length) {
      return -1;
    }
    CRC32C crc = new CRC32C();
    crc.update(bytes, 0, bytes.length - 8);
    if (ByteBuffer.wrap(bytes, bytes.length - 8, 8).getLong() != crc.getValue()) {
      return -1;
    }
    for (long i = 0; i < count; i++) {
      long keyHigh = buf.getLong();
      long keyLow = buf.getLong();
      int verified = buf.getInt();
      int seen = buf.getInt();
      byte method = buf.get();
      buf.position(buf.position() + 7);
      if (seen >= cutoff && verified > 0) {
        table.put(keyHigh, keyLow, verified, seen, method);
      }
    }
    return count;
  }

  /** Replays the journal; truncates it after its last valid entry; returns the valid entries. */
  private long replayJournal(VerifiedTable table, int cutoff) throws IOException {
    long size = journalChannel.size();
    ByteBuffer entry = ByteBuffer.allocate(ENTRY);
    long valid = 0;
    long position = 0;
    CRC32C crc = new CRC32C();
    while (position + ENTRY <= size) {
      entry.clear();
      while (entry.hasRemaining()) {
        if (journalChannel.read(entry, position + entry.position()) < 0) {
          break;
        }
      }
      crc.reset();
      crc.update(entry.array(), 0, ENTRY - 4);
      if ((int) crc.getValue() != entry.getInt(ENTRY - 4)) {
        break;
      }
      byte op = entry.get(0);
      byte method = entry.get(1);
      int verified = entry.getInt(4);
      int seen = entry.getInt(8);
      long keyHigh = entry.getLong(12);
      long keyLow = entry.getLong(20);
      if (op == PUT && seen >= cutoff && verified > 0) {
        table.put(keyHigh, keyLow, verified, seen, method);
      } else if (op == PUT || op == REMOVE) {
        table.remove(keyHigh, keyLow);
      } else {
        break;
      }
      valid++;
      position += ENTRY;
    }
    if (position < size) {
      logger.warn(
          "{}: cut off {} bytes after its last valid change (a crash mid-write)",
          journal,
          size - position);
      journalChannel.truncate(position);
    }
    journalChannel.position(position);
    return valid;
  }

  // ---------------------------------------------------------------------------
  // Journal
  // ---------------------------------------------------------------------------

  /**
   * Appends an upsert to the journal, in memory until the next {@link #flush()}.
   *
   * @throws IOException if the buffer was full and could not be written
   */
  void appendPut(long keyHigh, long keyLow, int verified, int seen, byte method)
      throws IOException {
    append(PUT, keyHigh, keyLow, verified, seen, method);
  }

  /**
   * Appends a removal to the journal, in memory until the next {@link #flush()}.
   *
   * @throws IOException if the buffer was full and could not be written
   */
  void appendRemove(long keyHigh, long keyLow) throws IOException {
    append(REMOVE, keyHigh, keyLow, 0, 0, (byte) 0);
  }

  private void append(byte op, long keyHigh, long keyLow, int verified, int seen, byte method)
      throws IOException {
    if (!pending.hasRemaining()) {
      flush();
    }
    int start = pending.position();
    pending.put(op).put(method).putShort((short) 0).putInt(verified).putInt(seen);
    pending.putLong(keyHigh).putLong(keyLow);
    CRC32C crc = new CRC32C();
    crc.update(pending.array(), start, ENTRY - 4);
    pending.putInt((int) crc.getValue());
    journalEntries++;
  }

  /**
   * Writes the buffered journal entries and forces them to disk.
   *
   * @throws IOException if they cannot be written
   */
  void flush() throws IOException {
    if (pending.position() == 0) {
      return;
    }
    pending.flip();
    while (pending.hasRemaining()) {
      journalChannel.write(pending);
    }
    pending.clear();
    journalChannel.force(false);
  }

  /**
   * Returns how many changes the journal holds since the last snapshot.
   *
   * @return the count
   */
  long journalEntries() {
    return journalEntries;
  }

  // ---------------------------------------------------------------------------
  // Snapshot
  // ---------------------------------------------------------------------------

  /**
   * Writes a snapshot of the table, then empties the journal. Called from the store's thread, the
   * only writer, so the table holds still meanwhile.
   *
   * @param table the table
   * @param cutoff the first minute still valid: older identities are left out
   * @throws IOException if the snapshot cannot be written; the journal is then kept
   */
  void snapshot(VerifiedTable table, int cutoff) throws IOException {
    Path temporary = directory.resolve(SNAPSHOT + ".tmp");
    long[] count = {0};
    CRC32C crc = new CRC32C();
    try (FileChannel out =
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ,
            StandardOpenOption.TRUNCATE_EXISTING)) {
      ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
      buf.putLong(MAGIC).putLong(0); // the count, rewritten once known
      IOException[] failure = {null};
      table.forEach(
          (keyHigh, keyLow, verified, seen, method) -> {
            if (failure[0] != null || seen < cutoff) {
              return;
            }
            try {
              if (buf.remaining() < ENTRY) {
                drain(out, buf);
              }
              buf.putLong(keyHigh).putLong(keyLow).putInt(verified).putInt(seen).put(method);
              buf.putInt(0).putShort((short) 0).put((byte) 0); // padding to 32 bytes
              count[0]++;
            } catch (IOException e) {
              failure[0] = e;
            }
          });
      if (failure[0] != null) {
        throw failure[0];
      }
      drain(out, buf);
      // The count goes at offset 8, and the checksum covers it: compute it over the final bytes.
      ByteBuffer header = ByteBuffer.allocate(HEADER).putLong(MAGIC).putLong(count[0]).flip();
      out.write(header, 0);
      crc.reset();
      ByteBuffer all = ByteBuffer.allocate(64 * 1024);
      long position = 0;
      long size = out.size();
      while (position < size) {
        all.clear();
        int read = out.read(all, position);
        if (read <= 0) {
          break;
        }
        crc.update(all.array(), 0, read);
        position += read;
      }
      out.write(ByteBuffer.allocate(8).putLong(crc.getValue()).flip(), size);
      out.force(true);
    }
    try {
      Files.move(
          temporary, snapshot, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, snapshot, StandardCopyOption.REPLACE_EXISTING);
    }
    // Everything the journal held is in the snapshot now.
    pending.clear();
    journalChannel.truncate(0);
    journalChannel.position(0);
    journalChannel.force(true);
    journalEntries = 0;
  }

  private static void drain(FileChannel out, ByteBuffer buf) throws IOException {
    buf.flip();
    while (buf.hasRemaining()) {
      out.write(buf);
    }
    buf.clear();
  }

  @Override
  public void close() throws IOException {
    try {
      flush();
    } finally {
      journalChannel.close();
    }
  }
}
