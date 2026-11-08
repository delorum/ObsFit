package ru.obsfit;

import static org.junit.Assert.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.Test;

public class FoodHistoryTest {
  private static final LocalDate START = LocalDate.of(2026, 1, 1);

  private static final class Storage implements VaultStorage {
    final Fixtures.MemoryStorage memory = new Fixtures.MemoryStorage();
    final List<String> reads = new ArrayList<>();
    String fail;

    Storage() {
      memory.data.clear();
    }

    String add(LocalDate date, String body) {
      String path = DiaryLayout.weekFolder(date) + "/" + date + "-заметка.md";
      memory.data.put(path, "## Питание\n### Обед\n" + body + "\n");
      return path;
    }

    public void validateRoot() {}

    public void requireDirectory(String path) {}

    public List<Entry> children(String path) throws IOException {
      return memory.children(path);
    }

    public String read(String path) throws IOException {
      reads.add(path);
      if (path.equals(fail)) throw new IOException("Нет доступа: " + path);
      return memory.read(path);
    }

    public void write(String path, String text) {
      throw new AssertionError("History must not write");
    }
  }

  private FoodHistory.Search search(Storage storage, String name) {
    var catalog = Fixtures.catalog().catalog;
    return new VaultRepository(storage, new Fixtures.MemoryBackups())
        .foodHistory(catalog, catalog.get(name));
  }

  @Test
  public void stopsReadingContentsImmediatelyAfterTwentyMatchesAndContinuesBackwards()
      throws Exception {
    var storage = new Storage();
    for (int i = 0; i < 60; i++) storage.add(START.plusDays(i), "хлеб 40 г 80 ккал");
    var search = search(storage, "хл");
    var first = search.nextPage();
    assertEquals(20, first.hits().size());
    assertEquals(20, storage.reads.size());
    assertEquals(START.plusDays(59), first.hits().get(0).date());
    assertEquals(START.plusDays(40), first.hits().get(19).date());
    var second = search.nextPage();
    assertEquals(20, second.hits().size());
    assertEquals(40, storage.reads.size());
    assertEquals(START.plusDays(39), second.hits().get(0).date());
    assertEquals(20, search.nextPage().hits().size());
    assertTrue(search.nextPage().done());
    assertEquals(60, storage.reads.size());
  }

  @Test
  public void pageSizeCountsMatchingEntriesRatherThanVisitedNotes() throws Exception {
    var storage = new Storage();
    for (int i = 0; i < 100; i++)
      storage.add(START.plusDays(i), i % 5 == 0 ? "хл 40 г 80 ккал" : "сыр 20 г 60 ккал");
    var search = search(storage, "хлеб");
    var first = search.nextPage();
    assertEquals(20, first.hits().size());
    assertEquals(START.plusDays(95), first.hits().get(0).date());
    assertEquals(100, storage.reads.size());
    assertTrue(search.nextPage().done());
  }

  @Test
  public void paginatesInsideOneDayWithoutRereadingOrDuplicatingRows() throws Exception {
    var storage = new Storage();
    var body = new StringBuilder();
    for (int i = 1; i <= 45; i++)
      body.append("хлеб ").append(i).append(" г ").append(i * 2).append(" ккал\n");
    storage.add(START, body.toString());
    var search = search(storage, "хлеб");
    var first = search.nextPage();
    var second = search.nextPage();
    var third = search.nextPage();
    assertEquals(20, first.hits().size());
    assertEquals(20, second.hits().size());
    assertEquals(5, third.hits().size());
    assertTrue(third.done());
    assertEquals(1, storage.reads.size());
    assertEquals(45, first.hits().get(0).grams(), 0);
    assertEquals(25, second.hits().get(0).grams(), 0);
    assertEquals(1, third.hits().get(4).grams(), 0);
  }

  @Test
  public void usesStoredCaloriesEvenIfCatalogEnergyHasChanged() throws Exception {
    var storage = new Storage();
    storage.add(START, "ХЛ 40 г 80 ккал");
    var catalog = VaultMarkdown.catalog(Fixtures.FOOD.replace("200 | 40", "400 | 40"));
    var search = new FoodHistory.Search(storage, catalog, catalog.get("хлеб"));
    var hit = search.nextPage().hits().get(0);
    assertEquals(80, hit.calories(), 0);
    assertFalse(hit.estimated());
    assertEquals(40, hit.grams(), 0);
    assertEquals(160, VaultMarkdown.day("хл 40 г 80 ккал", catalog).total, 0);
  }

  @Test
  public void supportsRenamedProductsAndAliasesWithoutMatchingProseOrManualCalories()
      throws Exception {
    var storage = new Storage();
    storage.add(
        START,
        "хлеб 40 г 80 ккал\n"
            + "хл 20 г 40 ккал\n"
            + "Съел хлеб 30 г 60 ккал\n"
            + "хлеб 80 ккал\n"
            + "сыр 20 г 60 ккал");
    var catalog =
        VaultMarkdown.catalog(Fixtures.FOOD.replace("хлеб<br>хл", "новый хлеб<br>хлеб<br>хл"));
    var result = new FoodHistory.Search(storage, catalog, catalog.get("новый хлеб")).nextPage();
    assertEquals(2, result.hits().size());
  }

  @Test
  public void ignoresProtectedMarkdownAndReportsEstimatesForUnformattedEntries() throws Exception {
    var storage = new Storage();
    storage.add(
        START,
        "```\n"
            + "хл 40 г 80 ккал\n"
            + "```\n"
            + "> хл 40 г 80 ккал\n"
            + "<!-- хл 40 г 80 ккал -->\n"
            + "%% хл 40 г 80 ккал %%\n"
            + "хл 40\n");
    var hits = search(storage, "хлеб").nextPage().hits();
    assertEquals(1, hits.size());
    assertTrue(hits.get(0).estimated());
    assertEquals(80, hits.get(0).calories(), 0);
  }

  @Test
  public void retryRetainsPartialPageAndRetriesFailedFileWithoutSkippingHits() throws Exception {
    var storage = new Storage();
    var old = new StringBuilder();
    var recent = new StringBuilder();
    for (int i = 1; i <= 15; i++) old.append("хл ").append(i).append(" г 1 ккал\n");
    for (int i = 1; i <= 10; i++) recent.append("хл ").append(i).append(" г 1 ккал\n");
    String failed = storage.add(START, old.toString());
    storage.add(START.plusDays(1), recent.toString());
    storage.fail = failed;
    var search = search(storage, "хл");
    assertThrows(IOException.class, search::nextPage);
    storage.fail = null;
    var first = search.nextPage();
    assertEquals(20, first.hits().size());
    assertEquals(START.plusDays(1), first.hits().get(0).date());
    assertEquals(START, first.hits().get(10).date());
    assertEquals(5, search.nextPage().hits().size());
    assertEquals(3, storage.reads.size());
  }

  @Test
  public void cancellationStopsAfterCurrentReadWithoutScanningRemainingArchive() throws Exception {
    var storage = new Storage();
    for (int i = 0; i < 60; i++) storage.add(START.plusDays(i), "сыр 20 г 60 ккал");
    var search = search(storage, "хл");
    storage.memory.onRead = path -> search.cancel();
    assertThrows(CancellationException.class, search::nextPage);
    assertEquals(1, storage.reads.size());
  }

  @Test
  public void ordersAcrossCalendarAndIsoWeekYearsAndHandlesEmptyArchive() throws Exception {
    var storage = new Storage();
    storage.add(LocalDate.of(2025, 12, 31), "хл 10 г 20 ккал");
    storage.add(LocalDate.of(2026, 1, 1), "хл 20 г 40 ккал");
    var hits = search(storage, "хл").nextPage().hits();
    assertEquals(LocalDate.of(2026, 1, 1), hits.get(0).date());
    assertEquals(LocalDate.of(2025, 12, 31), hits.get(1).date());
    var empty = search(new Storage(), "хл").nextPage();
    assertTrue(empty.done());
    assertTrue(empty.hits().isEmpty());
  }

  @Test
  public void refusesDuplicateDailyDatesRatherThanCountingSyncthingConflictsTwice()
      throws Exception {
    var storage = new Storage();
    String path = storage.add(START, "хл 10 г 20 ккал");
    storage.memory.data.put(path.replace("-заметка.md", "-копия.md"), "хл 10 г 20 ккал");
    assertThrows(IOException.class, () -> search(storage, "хл").nextPage());
  }

  @Test
  public void compositeHistoryDoesNotDuplicateExplicitRecipesFromLeavesInTheSameMeal()
      throws Exception {
    var storage = new Storage();
    storage.add(START, "бутерброд 60 г 140 ккал\nхлеб 40 г 80 ккал\nсыр 20 г 60 ккал");
    var hits = search(storage, "ббр").nextPage().hits();
    assertEquals(1, hits.size());
    assertEquals(60, hits.get(0).grams(), 0);
  }

  @Test
  public void historyBackPreservesSelectedPortionAndUnsavedWeight() {
    var c = new AppController();
    c.day(Fixtures.day());
    c.portion("Обед", null);
    c.chooseFood(c.state.day.catalog.get("хл"));
    c.state.portion.changePortionWeight("55");
    var form = c.state.portion;
    c.foodHistory(320);
    assertEquals(AppState.Screen.FOOD_HISTORY, c.state.screen);
    assertEquals(320, c.state.foodHistory.sourceScroll);
    c.closeFoodHistory();
    assertSame(form, c.state.portion);
    assertEquals("55", c.state.portion.weight);
    assertTrue(c.state.portion.dirty);
    assertEquals(AppState.Screen.PORTION, c.state.screen);
  }

  @Test
  public void historyFromCatalogUsesSavedIdentityAndPreservesUnsavedRename() {
    var c = new AppController();
    c.editCatalog(Fixtures.catalog(), Fixtures.catalog().catalog.get("хл"), false);
    c.state.catalogForm.name = "не сохранённое имя";
    c.state.catalogForm.dirty = true;
    c.foodHistory(150);
    assertEquals("хлеб", c.state.foodHistory.food.name);
    assertSame(c.state.catalogForm.original.catalog.get("хлеб"), c.state.foodHistory.food);
    c.closeFoodHistory();
    assertEquals("не сохранённое имя", c.state.catalogForm.name);
    assertTrue(c.state.catalogForm.dirty);
    assertEquals(AppState.Screen.CATALOG_EDITOR, c.state.screen);
  }

  @Test
  public void historyAndSourceFormRestoreAfterProcessRecreation() throws Exception {
    var c = new AppController();
    c.day(Fixtures.day());
    c.portion("Обед", null);
    c.chooseFood(c.state.day.catalog.get("хл"));
    c.state.portion.changePortionWeight("55");
    c.foodHistory(123);
    var bytes = new ByteArrayOutputStream();
    try (var out = new ObjectOutputStream(bytes)) {
      out.writeObject(c.state.save());
    }
    AppState.Saved saved;
    try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      saved = (AppState.Saved) in.readObject();
    }
    var restored = new AppController();
    restored.restore(saved, List.of(Fixtures.day().note), Fixtures.day(), Fixtures.catalog());
    assertEquals(AppState.Screen.FOOD_HISTORY, restored.state.screen);
    assertEquals("хлеб", restored.state.foodHistory.food.name);
    assertTrue(restored.state.foodHistory.hits.isEmpty());
    restored.closeFoodHistory();
    assertEquals("55", restored.state.portion.weight);
  }

  private AppState.Saved roundTrip(AppState state) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var out = new ObjectOutputStream(bytes)) {
      out.writeObject(state.save());
    }
    try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      return (AppState.Saved) in.readObject();
    }
  }

  @Test
  public void catalogHistoryRestoreKeepsUnsavedEditorAndReturnPortion() throws Exception {
    var c = new AppController();
    c.day(Fixtures.day());
    c.portion("Обед", null);
    c.chooseFood(c.state.day.catalog.get("хл"));
    c.state.portion.changePortionWeight("55");
    c.editCatalog(Fixtures.catalog(), c.state.day.catalog.get("хл"), true);
    c.state.catalogForm.name = "Новое имя";
    c.state.catalogForm.dirty = true;
    c.foodHistory(80);
    var restored = new AppController();
    restored.restore(
        roundTrip(c.state), List.of(Fixtures.day().note), Fixtures.day(), Fixtures.catalog());
    assertEquals(AppState.Screen.FOOD_HISTORY, restored.state.screen);
    restored.closeFoodHistory();
    assertEquals("Новое имя", restored.state.catalogForm.name);
    assertTrue(restored.state.catalogForm.dirty);
    assertNotNull(restored.state.returnPortion);
  }

  @Test
  public void historyDoesNotRestoreIntoAChangedDiaryEntry() throws Exception {
    var c = new AppController();
    c.day(Fixtures.day());
    c.portion("Заметки", c.state.day.day.entries.get(0));
    c.foodHistory(0);
    var changed =
        Fixtures.day(Fixtures.DAY.replace("40 г 80 ккал", "50 г 100 ккал"), Fixtures.FOOD);
    var restored = new AppController();
    restored.restore(roundTrip(c.state), List.of(changed.note), changed, Fixtures.catalog());
    assertEquals(AppState.Screen.DAY, restored.state.screen);
    assertNull(restored.state.foodHistory);
    assertNotNull(restored.state.error);
  }

  @Test
  public void unavailableAndNewProductsCannotOpenHistory() {
    var c = new AppController();
    c.day(Fixtures.day());
    assertThrows(IllegalStateException.class, () -> c.foodHistory(0));
    c.portion("Обед", null);
    assertThrows(IllegalStateException.class, () -> c.foodHistory(0));
    c.newFood();
    assertThrows(IllegalStateException.class, () -> c.foodHistory(0));
    c.editCatalog(Fixtures.catalog(), null, false);
    assertThrows(IllegalStateException.class, () -> c.foodHistory(0));
  }
}
