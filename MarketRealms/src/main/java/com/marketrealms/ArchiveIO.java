package com.marketrealms;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** No Java object deserialization, extracted files, executable payloads or ZIP paths. */
final class ArchiveIO {
  static String write(Path target, String manifest, long maximum) throws Exception {
    byte[] data = manifest.getBytes(StandardCharsets.UTF_8);
    if (data.length > maximum) throw new IOException("Archive exceeds expanded-data limit");
    Files.createDirectories(target.getParent());
    Path temp = target.resolveSibling(target.getFileName() + ".pending");
    try (var out = new ZipOutputStream(Files.newOutputStream(temp))) {
      out.putNextEntry(new ZipEntry("manifest.yml"));
      out.write(data);
      out.closeEntry();
    }
    try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
    try {
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
    String digest = checksum(target);
    if (!read(target, maximum, digest).equals(manifest))
      throw new IOException("Archive verification failed");
    return digest;
  }

  static String read(Path target, long maximum, String expected) throws Exception {
    if (!checksum(target).equals(expected))
      throw new IOException("Archive checksum does not match verified metadata");
    try (var zip = new ZipInputStream(Files.newInputStream(target));
        var out = new ByteArrayOutputStream()) {
      ZipEntry entry = zip.getNextEntry();
      if (entry == null || entry.isDirectory() || !entry.getName().equals("manifest.yml"))
        throw new IOException("Unexpected ZIP entry");
      byte[] buffer = new byte[8192];
      long total = 0;
      int n;
      while ((n = zip.read(buffer)) != -1) {
        total += n;
        if (total > maximum) throw new IOException("Archive exceeds expansion limit");
        out.write(buffer, 0, n);
      }
      zip.closeEntry();
      if (zip.getNextEntry() != null) throw new IOException("Multiple ZIP entries are not allowed");
      return out.toString(StandardCharsets.UTF_8);
    }
  }

  static String checksum(Path file) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var in = Files.newInputStream(file)) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) digest.update(b, 0, n);
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
