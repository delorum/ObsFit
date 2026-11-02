package ru.obsfit;

import static org.junit.Assert.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import org.junit.Test;

public class RefreshTest {
  private final StampedStorage storage = new StampedStorage();
  private final VaultRepository repo = new VaultRepository(storage, new Fixtures.MemoryBackups());
  private final VaultRepository.Note note =
      new VaultRepository.Note(LocalDate.of(2026, 11, 1), Fixtures.PATH);

  @Test
  public void unchangedMetadataDoesNotReadContentsOrListDirectories() throws Exception {
    var loaded = repo.load(note);
    int reads = storage.memory.reads;
    assertNull(repo.refresh(note, loaded, false));
    assertNull(repo.refresh(note, loaded, false));
    assertEquals(reads, storage.memory.reads);
    assertEquals(0, storage.lists);
  }

  @Test
  public void changedTimestampReloadsSameLengthContent() throws Exception {
    var loaded = repo.load(note);
    storage.update(Fixtures.PATH, Fixtures.DAY.replace("40 г 80", "50 г 80"));
    var refreshed = repo.refresh(note, loaded, false);
    assertTrue(refreshed.day.source.contains("50 г 80"));
    assertEquals(100, refreshed.day.entries.get(0).kcal, 0);
    assertNull(repo.refresh(note, refreshed, false));
  }

  @Test
  public void changedSizeAloneAlsoReloads() throws Exception {
    var loaded = repo.load(note);
    var old = storage.stamp(Fixtures.PATH);
    storage.update(Fixtures.PATH, Fixtures.DAY + "\nТекст\n");
    storage.metadata.put(Fixtures.PATH, new VaultStorage.Stamp(old.modified(), old.size() + 10));
    assertNotNull(repo.refresh(note, loaded, false));
  }

  @Test
  public void catalogChangeUpdatesCaloriesWithoutRewritingDiary() throws Exception {
    var loaded = repo.load(note);
    storage.update("Еда.md", Fixtures.FOOD.replace("200 | 40", "250 | 40"));
    var refreshed = repo.refresh(note, loaded, false);
    assertEquals(100, refreshed.day.entries.get(0).kcal, 0);
    assertEquals(0, storage.memory.writes);
    assertEquals(Fixtures.DAY, storage.memory.data.get(Fixtures.PATH));
  }

  @Test
  public void replacementsAreAlsoWatched() throws Exception {
    var loaded = repo.load(note);
    storage.update("Замены.md", Fixtures.REPLACEMENTS + "\nновое\n");
    var refreshed = repo.refresh(note, loaded, false);
    assertNotEquals(loaded.replacementsText, refreshed.replacementsText);
    assertNull(repo.refresh(note, refreshed, false));
  }

  @Test
  public void forceReadsEvenWhenBothMetadataValuesMatch() throws Exception {
    var loaded = repo.load(note);
    storage.memory.data.put(Fixtures.PATH, Fixtures.DAY.replace("40 г 80", "50 г 80"));
    assertNull(repo.refresh(note, loaded, false));
    assertTrue(repo.refresh(note, loaded, true).day.source.contains("50 г 80"));
  }

  @Test
  public void failedReadDoesNotCommitNewMetadataAndIsRetried() throws Exception {
    var loaded = repo.load(note);
    storage.update(Fixtures.PATH, Fixtures.DAY + "новый текст\n");
    storage.failRead = true;
    assertThrows(IOException.class, () -> repo.refresh(note, loaded, false));
    storage.failRead = false;
    var refreshed = repo.refresh(note, loaded, false);
    assertTrue(refreshed.day.source.endsWith("новый текст\n"));
    assertNull(repo.refresh(note, refreshed, false));
  }

  @Test
  public void changingFileDuringReadDoesNotMarkSnapshotAsStable() throws Exception {
    storage.memory.onRead =
        path -> {
          if (path.equals(Fixtures.PATH)) {
            storage.update(Fixtures.PATH, Fixtures.DAY + "синхронизация\n");
            storage.memory.onRead = ignored -> {};
          }
        };
    var loaded = repo.load(note);
    int reads = storage.memory.reads;
    assertNotNull(repo.refresh(note, loaded, false));
    assertTrue(storage.memory.reads > reads);
  }

  @Test
  public void unknownMetadataFallsBackToReading() throws Exception {
    storage.metadata.put(Fixtures.PATH, new VaultStorage.Stamp(null, null));
    var loaded = repo.load(note);
    int reads = storage.memory.reads;
    assertNotNull(repo.refresh(note, loaded, false));
    assertTrue(storage.memory.reads > reads);
  }

  @Test
  public void metadataFailureDoesNotPreventReadableFilesFromLoading() throws Exception {
    storage.failStamp = true;
    var loaded = repo.load(note);
    assertNotNull(loaded);
    assertNotNull(repo.refresh(note, loaded, false));
  }

  @Test
  public void skippedUiResultIsDeliveredAgainFromCacheWithoutReading() throws Exception {
    var displayed = repo.load(note);
    storage.update(Fixtures.PATH, Fixtures.DAY + "новое\n");
    var skipped = repo.refresh(note, displayed, false);
    int reads = storage.memory.reads;
    assertSame(skipped, repo.refresh(note, displayed, false));
    assertEquals(reads, storage.memory.reads);
  }

  @Test
  public void switchingDaysCannotReuseAnotherDaysCache() throws Exception {
    var first = repo.load(note);
    String path = Fixtures.PATH.replace("11-01-вс", "11-02-пн");
    storage.update(path, Fixtures.DAY + "второй день\n");
    var second =
        repo.refresh(new VaultRepository.Note(LocalDate.of(2026, 11, 2), path), first, false);
    assertEquals(path, second.note.path);
    assertEquals(Fixtures.PATH, repo.refresh(note, first, false).note.path);
  }

  @Test
  public void failedForcedReadIsRetriedEvenIfMetadataStillMatches() throws Exception {
    var loaded = repo.load(note);
    storage.failRead = true;
    assertThrows(IOException.class, () -> repo.refresh(note, loaded, true));
    storage.failRead = false;
    int reads = storage.memory.reads;
    assertNotNull(repo.refresh(note, loaded, false));
    assertTrue(storage.memory.reads > reads);
  }

  @Test
  public void invalidSyncedCatalogDoesNotPoisonSuccessfulCache() throws Exception {
    var loaded = repo.load(note);
    storage.update("Еда.md", Fixtures.FOOD.replace("200 | 40", "oops | 40"));
    assertThrows(IllegalArgumentException.class, () -> repo.refresh(note, loaded, false));
    storage.update("Еда.md", Fixtures.FOOD);
    var refreshed = repo.refresh(note, loaded, false);
    assertNotNull(refreshed);
    assertNull(repo.refresh(note, refreshed, false));
  }

  private static final class StampedStorage implements VaultStorage {
    final Fixtures.MemoryStorage memory = new Fixtures.MemoryStorage();
    final Map<String, Stamp> metadata = new HashMap<>();
    long revision = 1;
    int lists;
    boolean failRead, failStamp;

    StampedStorage() {
      memory.data.forEach((path, text) -> metadata.put(path, new Stamp(1L, (long) text.length())));
    }

    void update(String path, String text) {
      memory.data.put(path, text);
      metadata.put(path, new Stamp(++revision, (long) text.length()));
    }

    public Stamp stamp(String path) throws IOException {
      if (failStamp) throw new IOException("metadata unavailable");
      return metadata.get(path);
    }

    public String read(String path) throws IOException {
      if (failRead) throw new IOException("temporarily unavailable");
      return memory.read(path);
    }

    public void write(String path, String text) {
      update(path, text);
    }

    public List<Entry> children(String path) {
      lists++;
      return memory.children(path);
    }

    public void validateRoot() {}

    public void requireDirectory(String path) throws IOException {
      memory.requireDirectory(path);
    }
  }
}
