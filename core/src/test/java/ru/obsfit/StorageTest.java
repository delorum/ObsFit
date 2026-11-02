package ru.obsfit;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.Test;

public final class StorageTest {
  @Test
  public void savesDayOnlyAfterDurableBackup() throws Exception {
    var files = new Fixtures.MemoryStorage();
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(files, backups);
    var original = repo.load(repo.notes().get(0));
    String updated =
        VaultMarkdown.add(original.day.source, "Завтрак", "сыр 20 г 60 ккал", original.catalog);
    repo.save(original, updated, null);
    assertEquals(updated, files.data.get(Fixtures.PATH));
    assertEquals(List.of(Fixtures.DAY), backups.saved);
    assertEquals(1, files.writes);
  }

  @Test
  public void rejectsSyncthingChangeBeforeAnyWrite() throws Exception {
    var files = new Fixtures.MemoryStorage();
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(files, backups);
    var original = repo.load(repo.notes().get(0));
    files.data.put(Fixtures.PATH, Fixtures.DAY + "Изменено на ноутбуке\n");
    assertThrows(
        VaultRepository.StaleSnapshot.class,
        () -> repo.save(original, Fixtures.DAY + "сыр 20 г 60 ккал", null));
    assertEquals(0, files.writes);
    assertTrue(backups.saved.isEmpty());
    assertTrue(files.data.get(Fixtures.PATH).endsWith("Изменено на ноутбуке\n"));
  }

  @Test
  public void rejectsChangeWhileBackupWasBeingSaved() throws Exception {
    var files = new Fixtures.MemoryStorage();
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(files, backups);
    var original = repo.load(repo.notes().get(0));
    backups.afterSave = () -> files.data.put(Fixtures.PATH, "Внешнее изменение");
    assertThrows(
        VaultRepository.StaleSnapshot.class,
        () -> repo.save(original, Fixtures.DAY + "updated", null));
    assertEquals(0, files.writes);
    assertEquals("Внешнее изменение", files.data.get(Fixtures.PATH));
    assertEquals(List.of(Fixtures.DAY), backups.saved);
  }

  @Test
  public void backupFailurePreventsTruncatingDocument() throws Exception {
    var files = new Fixtures.MemoryStorage();
    var backups = new Fixtures.MemoryBackups();
    backups.fail = true;
    var repo = new VaultRepository(files, backups);
    var original = repo.load(repo.notes().get(0));
    assertThrows(IOException.class, () -> repo.save(original, "updated", null));
    assertEquals(0, files.writes);
    assertEquals(Fixtures.DAY, files.data.get(Fixtures.PATH));
  }

  @Test
  public void partialSaveKeepsProductAndReportsFailedDay() throws Exception {
    var files = new Fixtures.MemoryStorage();
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(files, backups);
    var original = repo.load(repo.notes().get(0));
    files.failWrite = Fixtures.PATH;
    var food = new VaultMarkdown.Food("каша", List.of("кш"), "", 150, 100);
    var error =
        assertThrows(VaultRepository.PartialSave.class, () -> repo.save(original, "new day", food));
    assertTrue(error.getMessage().contains("Еда сохранена"));
    assertNotNull(VaultMarkdown.catalog(files.data.get("Еда.md")).get("кш"));
    assertEquals(Fixtures.DAY, files.data.get(Fixtures.PATH));
    assertEquals(List.of(Fixtures.FOOD, Fixtures.DAY), backups.saved);
  }

  @Test
  public void truncationIsDetectedAndOriginalIsRecoverable() throws Exception {
    var files = new Fixtures.MemoryStorage();
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(files, backups);
    var original = repo.load(repo.notes().get(0));
    files.truncateWrite = Fixtures.PATH;
    var error =
        assertThrows(IOException.class, () -> repo.save(original, "changed note body", null));
    assertTrue(error.getMessage().contains("backup-1"));
    assertEquals(Fixtures.DAY, backups.saved.get(0));
  }

  @Test
  public void changedReplacementsPreventCatalogSave() throws Exception {
    var files = new Fixtures.MemoryStorage();
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(files, backups);
    var original = repo.loadCatalog();
    files.data.put("Замены.md", Fixtures.REPLACEMENTS + "new alias");
    assertThrows(
        VaultRepository.StaleSnapshot.class, () -> repo.saveCatalog(original, Fixtures.FOOD));
    assertEquals(0, files.writes);
  }

  @Test
  public void listsExistingDatesAndRejectsDuplicates() throws Exception {
    var files = new Fixtures.MemoryStorage();
    files.data.put("Дневник/Хроника/2026/2026-11-03-вт.md", Fixtures.DAY);
    files.data.put("Дневник/Хроника/file.sync-conflict.md", Fixtures.DAY);
    var repo = new VaultRepository(files, new Fixtures.MemoryBackups());
    assertEquals(
        List.of(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3)),
        repo.notes().stream().map(n -> n.date).toList());
    files.data.put("Дневник/Хроника/duplicate/2026-11-01-вс.md", Fixtures.DAY);
    assertThrows(IOException.class, () -> repo.notes(LocalDate.of(2026, 11, 1), true));
  }

  @Test
  public void unchangedNoteDoesNotCreateBackup() throws Exception {
    var files = new Fixtures.MemoryStorage();
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(files, backups);
    var snapshot = repo.load(repo.notes().get(0));
    repo.save(snapshot, snapshot.day.source, null);
    assertEquals(0, files.writes);
    assertTrue(backups.saved.isEmpty());
  }

  @Test
  public void realBackupIsExactAndRetentionKeepsLatest() throws Exception {
    Path temporary = Files.createTempDirectory("obsfit-backups");
    try {
      FileBackups backups = new FileBackups(temporary.toFile(), 2);
      String first = backups.save("../Еда.md", "кириллица\r\n");
      assertEquals("кириллица\r\n", Files.readString(temporary.resolve(first)));
      assertEquals(temporary, temporary.resolve(first).getParent());
      backups.save("дневник.md", "second");
      String latest = backups.save("дневник.md", "third");
      assertEquals(2, Objects.requireNonNull(temporary.toFile().listFiles()).length);
      assertEquals("third", Files.readString(temporary.resolve(latest)));
    } finally {
      try (var files = Files.walk(temporary)) {
        for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
      }
    }
  }
}
