package ru.obsfit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Private durable backup files. Independent of Android and testable in a temporary directory. */
public final class FileBackups implements BackupStore {
  private final File directory;
  private final int retain;

  public FileBackups(File directory) {
    this(directory, 100);
  }

  public FileBackups(File directory, int retain) {
    if (retain < 1) throw new IllegalArgumentException("retain");
    this.directory = directory;
    this.retain = retain;
  }

  public File directory() {
    return directory;
  }

  @Override
  public synchronized String save(String filename, String original) throws IOException {
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IOException("Не удалось создать резервную копию");
    String safeName = filename.replaceAll("[^\\p{L}\\p{N}_.-]", "_");
    File backup =
        new File(directory, System.currentTimeMillis() + "-" + UUID.randomUUID() + "-" + safeName);
    try (FileOutputStream out = new FileOutputStream(backup)) {
      out.write(original.getBytes(StandardCharsets.UTF_8));
      out.getFD().sync();
    }
    File[] files = directory.listFiles(File::isFile);
    if (files != null && files.length > retain) {
      Arrays.sort(files, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
      int excess = files.length - retain;
      for (File file : files) if (excess > 0 && !file.equals(backup) && file.delete()) excess--;
    }
    return backup.getName();
  }
}
