package ru.obsfit;

import java.io.IOException;

public interface BackupStore {
  /** Persist the original text before any write and return a user-visible backup identifier. */
  String save(String filename, String original) throws IOException;
}
