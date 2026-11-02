package ru.obsfit;

import java.io.IOException;
import java.util.List;

/** Files are addressed by vault-relative paths. No Android types cross this boundary. */
public interface VaultStorage {
  /** Missing metadata is deliberately not treated as an unchanged file. */
  record Stamp(Long modified, Long size) {
    public boolean known() {
      return modified != null && modified >= 0 && size != null && size >= 0;
    }
  }

  default Stamp stamp(String path) throws IOException {
    return new Stamp(null, null);
  }

  void validateRoot() throws IOException;

  void requireDirectory(String path) throws IOException;

  String read(String path) throws IOException;

  void write(String path, String text) throws IOException;

  record Entry(String path, boolean directory) {}

  /** Direct children only: one provider query, without descending into subdirectories. */
  List<Entry> children(String directory) throws IOException;
}
