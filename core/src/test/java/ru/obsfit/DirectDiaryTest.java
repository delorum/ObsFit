package ru.obsfit;

import static org.junit.Assert.*;

import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.Test;

public class DirectDiaryTest {
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

  private static String path(LocalDate date) {
    return DiaryLayout.weekFolder(date) + "/" + date + " заметка.md";
  }

  private static final class Storage implements VaultStorage {
    final Fixtures.MemoryStorage memory = new Fixtures.MemoryStorage();
    final List<String> queries = new ArrayList<>();
    boolean fail;

    Storage() {
      memory.data.clear();
    }

    void add(LocalDate day) {
      memory.data.put(path(day), Fixtures.DAY);
    }

    public List<Entry> children(String path) throws IOException {
      queries.add(path);
      if (fail) throw new IOException("provider failure");
      return memory.children(path);
    }

    public void validateRoot() {}

    public void requireDirectory(String path) {}

    public String read(String path) throws IOException {
      return memory.read(path);
    }

    public void write(String path, String text) throws IOException {
      memory.write(path, text);
    }
  }

  @Test
  public void computesMomentRussianFolderAtCalendarAndWeekYearBoundaries() {
    assertEquals(
        "Дневник/Хроника/2026/1/01-январь/2026-1-неделя",
        DiaryLayout.weekFolder(LocalDate.of(2026, 1, 1)));
    assertEquals(
        "Дневник/Хроника/2027/1/01-январь/2026-53-неделя",
        DiaryLayout.weekFolder(LocalDate.of(2027, 1, 1)));
    assertEquals("Дневник/Хроника/2026/4/11-ноябрь/2026-45-неделя", DiaryLayout.weekFolder(TODAY));
    assertEquals(
        "Дневник/Хроника/2026/4/10-октябрь/2026-44-неделя",
        DiaryLayout.weekFolder(LocalDate.of(2026, 10, 31)));
    assertEquals(
        "Дневник/Хроника/2026/4/11-ноябрь/2026-44-неделя",
        DiaryLayout.weekFolder(LocalDate.of(2026, 11, 1)));
  }

  @Test
  public void opensExactWeekBeforeInspectingAnyParentOrArchive() throws Exception {
    var storage = new Storage();
    for (int i = 0; i < 7000; i++) storage.add(TODAY.minusDays(i));
    var index = new NoteIndex(storage);
    var initial = index.window(TODAY, false);
    assertEquals(3, initial.size());
    assertEquals(List.of(DiaryLayout.weekFolder(TODAY)), storage.queries);
    assertEquals(0, storage.memory.reads);
    var neighbors = index.extend(TODAY);
    assertEquals(8, neighbors.size());
    assertEquals(TODAY.minusDays(7), neighbors.get(0).date);
    assertFalse(storage.queries.stream().anyMatch(p -> p.contains("/2025")));
  }

  @Test
  public void missingTodayCanShowYesterdayFromExactWeekWithoutRootQueries() throws Exception {
    var storage = new Storage();
    storage.add(TODAY.minusDays(1));
    var index = new NoteIndex(storage);
    var initial = index.window(TODAY, false);
    assertEquals(TODAY.minusDays(1), AppController.nearest(initial, TODAY));
    assertEquals(List.of(DiaryLayout.weekFolder(TODAY)), storage.queries);
  }

  @Test
  public void backgroundRefreshRetainsVisitedDatesAndForegroundLoadDoesNotListFolders()
      throws Exception {
    var storage = new Storage();
    for (int i = 0; i < 100; i++) storage.add(TODAY.minusDays(i));
    storage.memory.data.put("Еда.md", Fixtures.FOOD);
    storage.memory.data.put("Замены.md", Fixtures.REPLACEMENTS);
    var repo = new VaultRepository(storage, new Fixtures.MemoryBackups());
    repo.notes(TODAY, false);
    repo.moreNotes(TODAY);
    for (int i = 5; i <= 35; i += 5) repo.moreNotes(TODAY.minusDays(i));
    int visited = repo.notes(TODAY, false).size();
    assertTrue(visited > 30);
    storage.queries.clear();
    var current =
        repo.notes(TODAY, false).stream()
            .filter(n -> n.date.equals(TODAY))
            .findFirst()
            .orElseThrow();
    repo.load(current);
    assertTrue(storage.queries.isEmpty());
    storage.add(TODAY.plusDays(1));
    storage.memory.data.remove(path(TODAY.minusDays(1)));
    var refreshed = repo.refreshNotes(TODAY);
    assertTrue(refreshed.stream().anyMatch(n -> n.date.equals(TODAY.plusDays(1))));
    assertFalse(refreshed.stream().anyMatch(n -> n.date.equals(TODAY.minusDays(1))));
    assertTrue(refreshed.stream().anyMatch(n -> n.date.equals(TODAY.minusDays(35))));
    assertTrue(refreshed.size() >= visited);
  }

  @Test
  public void failedRefreshKeepsLastSuccessfulDateCache() throws Exception {
    var storage = new Storage();
    storage.add(TODAY);
    var index = new NoteIndex(storage);
    var original = index.window(TODAY, false);
    storage.fail = true;
    assertThrows(IOException.class, () -> index.refreshNearby(TODAY));
    assertEquals(original, index.window(TODAY, false));
    storage.fail = false;
    storage.add(TODAY.plusDays(1));
    assertEquals(2, index.refreshNearby(TODAY).size());
  }

  @Test
  public void acceptsDatePrefixWithAnyMarkdownSuffixAndRejectsInvalidDates() {
    assertEquals(TODAY, VaultMarkdown.date("2026-11-04.md"));
    assertEquals(TODAY, VaultMarkdown.date("2026-11-04 заметка.md"));
    assertEquals(TODAY, VaultMarkdown.date("2026-11-04-ср.md"));
    assertNull(VaultMarkdown.date("2026-02-30.md"));
    assertNull(VaultMarkdown.date("2026-11-04.webp"));
    assertNull(VaultMarkdown.date("2026-11-04-ср.sync-conflict.md"));
  }

  @Test
  public void constructsLocalDocumentIdsWithoutGuessingOpaqueProviderIds() {
    String authority = "com.android.externalstorage.documents";
    assertEquals(
        "primary:Documents/obs/YaDiskVera/Дневник/Хроника",
        DiaryLayout.externalDocumentId(
            authority, "primary:Documents/obs/YaDiskVera", "Дневник/Хроника"));
    assertEquals(
        "primary:Дневник/Хроника",
        DiaryLayout.externalDocumentId(authority, "primary:", "Дневник/Хроника"));
    assertNull(DiaryLayout.externalDocumentId("com.example.cloud", "opaque-id", "a/b"));
    assertThrows(
        IllegalArgumentException.class,
        () -> DiaryLayout.externalDocumentId(authority, "primary:Documents/obs", "../other"));
  }
}
