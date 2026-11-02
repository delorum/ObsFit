package ru.obsfit;

import static org.junit.Assert.*;

import java.io.IOException;
import java.time.*;
import java.time.temporal.IsoFields;
import java.util.*;
import org.junit.Test;

public class NoteIndexTest {
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

  private static String path(LocalDate day) {
    String weekday =
        new String[] {"пн", "вт", "ср", "чт", "пт", "сб", "вс"}[day.getDayOfWeek().getValue() - 1];
    return String.format(
        Locale.ROOT,
        "%s/%04d/%d/%02d-месяц/%04d-%02d-неделя/%s-%s.md",
        NoteIndex.ROOT,
        day.getYear(),
        (day.getMonthValue() - 1) / 3 + 1,
        day.getMonthValue(),
        day.get(IsoFields.WEEK_BASED_YEAR),
        day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
        day,
        weekday);
  }

  private static final class Storage implements VaultStorage {
    final Fixtures.MemoryStorage memory = new Fixtures.MemoryStorage();
    final List<String> queries = new ArrayList<>();
    String failPath;

    Storage() {
      memory.data.clear();
    }

    void add(LocalDate date) {
      memory.data.put(path(date), Fixtures.DAY);
    }

    public List<Entry> children(String directory) throws IOException {
      queries.add(directory);
      if (directory.equals(failPath)) throw new IOException("provider failed");
      return memory.children(directory);
    }

    public String read(String path) throws IOException {
      return memory.read(path);
    }

    public void write(String path, String text) {
      throw new AssertionError("Unexpected write");
    }

    public void validateRoot() {}

    public void requireDirectory(String path) {}
  }

  private static List<LocalDate> dates(List<VaultRepository.Note> notes) {
    return notes.stream().map(n -> n.date).toList();
  }

  @Test
  public void startupReadsOnlyNearestFoldersAndDoesNotReadAnyNoteContents() throws Exception {
    var storage = new Storage();
    for (int i = 0; i < 7000; i++) storage.add(TODAY.minusDays(i));
    storage.memory.data.put(
        NoteIndex.ROOT + "/2026/4/11-месяц/2026-45-неделя/attachments/a/b/c/image.webp", "image");
    var notes = new NoteIndex(storage).window(TODAY, false);
    assertEquals(8, notes.size());
    assertEquals(TODAY.minusDays(7), notes.get(0).date);
    assertEquals(TODAY, notes.get(7).date);
    assertTrue(storage.queries.size() < 15);
    assertFalse(
        storage.queries.stream().anyMatch(p -> p.contains("/2025") || p.contains("attachments")));
    assertEquals(0, storage.memory.reads);
  }

  @Test
  public void navigatingAcrossWindowLoadsAnotherSmallPortionAndReusesMetadata() throws Exception {
    var storage = new Storage();
    for (int i = 0; i < 100; i++) storage.add(TODAY.minusDays(i));
    var index = new NoteIndex(storage);
    index.window(TODAY, false);
    int initial = storage.queries.size();
    index.window(TODAY, false);
    assertEquals(initial, storage.queries.size());
    index.extend(TODAY.minusDays(4));
    assertEquals(initial, storage.queries.size());
    var next = index.extend(TODAY.minusDays(5));
    assertEquals(15, index.window(TODAY.minusDays(6), false).size());
    assertEquals(15, next.size());
    assertEquals(TODAY.minusDays(14), next.get(0).date);
    assertEquals(TODAY, next.get(14).date);
    assertTrue(storage.queries.size() - initial < 6);
  }

  @Test
  public void forwardPrefetchAddsOnePageAtTwoRemainingAndDoesNotRepeat() throws Exception {
    var storage = new Storage();
    for (int i = -50; i <= 50; i++) storage.add(TODAY.plusDays(i));
    var index = new NoteIndex(storage);
    assertEquals(15, index.window(TODAY, false).size());
    int queries = storage.queries.size();
    assertEquals(15, index.extend(TODAY.plusDays(4)).size());
    assertEquals(queries, storage.queries.size());
    var expanded = index.extend(TODAY.plusDays(5));
    assertEquals(22, expanded.size());
    assertEquals(TODAY.plusDays(14), expanded.get(21).date);
    queries = storage.queries.size();
    assertEquals(22, index.extend(TODAY.plusDays(5)).size());
    assertEquals(queries, storage.queries.size());
  }

  @Test
  public void skipsLongGapsAcrossMonthsQuartersAndYearsAndDisablesEdges() throws Exception {
    var storage = new Storage();
    List<LocalDate> existing =
        List.of(LocalDate.of(2020, 1, 1), LocalDate.of(2025, 12, 31), TODAY, TODAY.plusDays(2));
    existing.forEach(storage::add);
    var index = new NoteIndex(storage);
    var notes = index.window(TODAY, false);
    assertEquals(existing, dates(notes));
    var controller = new AppController();
    controller.state.notes = notes;
    controller.state.selected = TODAY;
    controller.navigate(-1);
    assertEquals(existing.get(1), controller.state.selected);
    controller.navigate(-1);
    assertFalse(controller.canNavigate(-1));
    controller.state.selected = TODAY.plusDays(2);
    assertFalse(controller.canNavigate(1));
  }

  @Test
  public void refreshedWindowFindsNewAndRemovedDaysWithoutReadingArchive() throws Exception {
    var storage = new Storage();
    for (int i = 0; i < 1000; i++) storage.add(TODAY.minusDays(i));
    var index = new NoteIndex(storage);
    index.window(TODAY, false);
    storage.add(TODAY.plusDays(1));
    storage.memory.data.remove(path(TODAY));
    storage.queries.clear();
    var notes = index.window(TODAY, true);
    assertFalse(dates(notes).contains(TODAY));
    assertTrue(dates(notes).contains(TODAY.plusDays(1)));
    assertTrue(storage.queries.size() < 16);
    assertFalse(storage.queries.stream().anyMatch(p -> p.contains("/2025")));
  }

  @Test
  public void emptyVaultAndMissingTodayChooseNearestPastOrFirstFuture() throws Exception {
    var storage = new Storage();
    var index = new NoteIndex(storage);
    assertTrue(index.window(TODAY, false).isEmpty());
    storage.add(TODAY.plusDays(20));
    var future = index.window(TODAY, true);
    assertEquals(TODAY.plusDays(20), AppController.nearest(future, TODAY));
    storage.add(TODAY.minusDays(10));
    var notes = index.window(TODAY, true);
    assertEquals(TODAY.minusDays(10), AppController.nearest(notes, TODAY));
  }

  @Test
  public void detectsConflictsEvenAtTheLastPositionOfAPage() throws Exception {
    var storage = new Storage();
    for (int i = 0; i < 20; i++) storage.add(TODAY.minusDays(i));
    storage.memory.data.put(path(TODAY.minusDays(7)).replace("-пн.md", "-вт.md"), Fixtures.DAY);
    assertThrows(IOException.class, () -> new NoteIndex(storage).window(TODAY, false));
  }

  @Test
  public void unknownFolderNamesRemainSupportedAndFailuresCanBeRetried() throws Exception {
    var storage = new Storage();
    storage.memory.data.put(NoteIndex.ROOT + "/другое/2026-11-02-пн.md", Fixtures.DAY);
    storage.failPath = NoteIndex.ROOT + "/другое";
    var index = new NoteIndex(storage);
    assertThrows(IOException.class, () -> index.window(TODAY, false));
    storage.failPath = null;
    assertEquals(List.of(TODAY), dates(index.window(TODAY, false)));
  }

  @Test
  public void windowMatchesSortedDatesAroundYearAndIsoWeekBoundaries() throws Exception {
    var storage = new Storage();
    TreeSet<LocalDate> all = new TreeSet<>();
    Random random = new Random(17);
    for (int i = 0; i < 250; i++) all.add(LocalDate.of(2024, 10, 1).plusDays(random.nextInt(500)));
    all.forEach(storage::add);
    var index = new NoteIndex(storage);
    for (int i = 0; i < 100; i++) {
      LocalDate around = LocalDate.of(2024, 9, 1).plusDays(random.nextInt(600));
      List<LocalDate> expected =
          new ArrayList<>(all.headSet(around, true).descendingSet().stream().limit(8).toList());
      expected.addAll(all.tailSet(around, false).stream().limit(7).toList());
      expected.sort(Comparator.naturalOrder());
      assertEquals("around " + around, expected, dates(index.window(around, true)));
    }
  }

  @Test
  public void connectingValidatesAccessWithoutDuplicateCatalogReadsAndLoadsOneDay()
      throws Exception {
    var storage = new Storage();
    storage.add(TODAY);
    storage.memory.data.put("Еда.md", Fixtures.FOOD);
    storage.memory.data.put("Замены.md", Fixtures.REPLACEMENTS);
    var repo = new VaultRepository(storage, new Fixtures.MemoryBackups());
    repo.validateRoot();
    assertEquals(0, storage.memory.reads);
    var notes = repo.notes(TODAY, true);
    repo.load(notes.get(0));
    assertEquals(3, storage.memory.reads);
    assertEquals(0, storage.memory.writes);
  }
}
