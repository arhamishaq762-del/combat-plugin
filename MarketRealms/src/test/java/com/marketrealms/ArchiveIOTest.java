package com.marketrealms;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveIOTest {
  @TempDir Path dir;

  @Test
  void verifiedArchiveRoundTrips() throws Exception {
    Path f = dir.resolve("build.mrzip");
    String data = "version: 1\nowner: uuid\nblocks: []\n";
    String hash = ArchiveIO.write(f, data, 10000);
    assertEquals(data, ArchiveIO.read(f, 10000, hash));
    assertFalse(Files.exists(dir.resolve("build.mrzip.pending")));
  }

  @Test
  void tamperedArchiveRejected() throws Exception {
    Path f = dir.resolve("build.mrzip");
    String hash = ArchiveIO.write(f, "version: 1", 10000);
    Files.writeString(f, "bad");
    assertThrows(IOException.class, () -> ArchiveIO.read(f, 10000, hash));
  }

  @Test
  void writeLimitsDoNotReplacePreviousArchive() throws Exception {
    Path f = dir.resolve("build.mrzip");
    String hash = ArchiveIO.write(f, "good", 10000);
    assertThrows(IOException.class, () -> ArchiveIO.write(f, "too much data", 1));
    assertEquals("good", ArchiveIO.read(f, 10000, hash));
  }

  @Test
  void expansionLimitPreventsZipBomb() throws Exception {
    Path f = dir.resolve("big.mrzip");
    String hash = ArchiveIO.write(f, "a".repeat(100000), 200000);
    assertThrows(IOException.class, () -> ArchiveIO.read(f, 1000, hash));
  }

  @Test
  void traversalEntryRejected() throws Exception {
    Path f = dir.resolve("bad.mrzip");
    try (var zip = new ZipOutputStream(Files.newOutputStream(f))) {
      zip.putNextEntry(new ZipEntry("../../outside"));
      zip.write(1);
      zip.closeEntry();
    }
    assertThrows(IOException.class, () -> ArchiveIO.read(f, 10000, ArchiveIO.checksum(f)));
    assertFalse(Files.exists(dir.resolve("outside")));
  }

  @Test
  void extraEntriesRejected() throws Exception {
    Path f = dir.resolve("bad.mrzip");
    try (var zip = new ZipOutputStream(Files.newOutputStream(f))) {
      zip.putNextEntry(new ZipEntry("manifest.yml"));
      zip.write(1);
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("extra"));
      zip.write(2);
      zip.closeEntry();
    }
    assertThrows(IOException.class, () -> ArchiveIO.read(f, 10000, ArchiveIO.checksum(f)));
  }
}
