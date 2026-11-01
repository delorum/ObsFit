package ru.obsfit;

import java.io.IOException;
import java.util.List;

/** Files are addressed by vault-relative paths. No Android types cross this boundary. */
public interface VaultStorage {
  void validateRoot() throws IOException;

  void requireDirectory(String path) throws IOException;

  String read(String path) throws IOException;

  void write(String path, String text) throws IOException;

  List<String> files(String directory) throws IOException;
}
