package ru.obsfit;

import static org.junit.Assert.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.Test;

public class HistoryProgressTest {
  private static final LocalDate FIRST = FoodHistory.FIRST_DATE;
  private final VaultMarkdown.Catalog catalog =
      VaultMarkdown.catalog(
          Fixtures.FOOD.replace(
              "<!-- food-table:end -->",
              "| Мандарин | | 38 | 100 |\n| Red bull | | 45 | 250 |\n<!-- food-table:end -->"));

  private static final class Storage implements VaultStorage {
    final Fixtures.MemoryStorage memory = new Fixtures.MemoryStorage();
    final List<String> reads = new ArrayList<>(), folders = new ArrayList<>();
    String fail;

    Storage() {
      memory.data.clear();
    }

    String add(LocalDate date, String body) {
      String path = DiaryLayout.weekFolder(date) + "/" + date + "-день.md";
      memory.data.put(path, "### Обед\n" + body + "\n");
      return path;
    }

    public void validateRoot() {}

    public void requireDirectory(String path) {}

    public List<Entry> children(String path) {
      folders.add(path);
      return memory.children(path);
    }

    public String read(String path) throws IOException {
      reads.add(path);
      if (path.equals(fail)) throw new IOException("Ошибка чтения");
      return memory.read(path);
    }

    public void write(String path, String text) {
      fail("History is read-only");
    }
  }

  @Test
  public void oneRedBullAppearsBeforeReadingOtherNotesAndOldArchiveIsNotOpened() throws Exception {
    var storage = new Storage();
    for (int i = 1; i <= 500; i++) storage.add(FIRST.minusDays(i), "Red bull 250 г 113 ккал");
    storage.add(FIRST, "Мандарин 100 г 38 ккал");
    storage.add(FIRST.plusDays(1), "Мандарин 50 г 19 ккал");
    String latest = storage.add(FIRST.plusDays(2), "Red bull 250 г 113 ккал");
    var search = new FoodHistory.Search(storage, catalog, catalog.get("Red bull"));
    List<FoodHistory.Hit> shown = new ArrayList<>();
    var page =
        search.nextPage(
            progress -> {
              assertEquals(List.of(latest), storage.reads);
              shown.addAll(progress);
            });
    assertEquals(1, shown.size());
    assertEquals(1, page.hits().size());
    assertEquals(latest, page.hits().get(0).path());
    assertEquals(113, page.hits().get(0).calories(), 0);
    assertTrue(page.done());
    assertEquals(3, storage.reads.size());
    assertFalse(storage.folders.contains(NoteIndex.ROOT + "/2025"));
  }

  @Test
  public void cutoffIncludesNovemberFirstAndExcludesOctoberEvenWithMatchingFood() throws Exception {
    var storage = new Storage();
    storage.add(FIRST.minusDays(1), "Мандарин 200 г 76 ккал");
    String included = storage.add(FIRST, "Мандарин 100 г 38 ккал");
    var page = new FoodHistory.Search(storage, catalog, catalog.get("Мандарин")).nextPage();
    assertTrue(page.done());
    assertEquals(1, page.hits().size());
    assertEquals(FIRST, page.hits().get(0).date());
    assertEquals(List.of(included), storage.reads);
  }

  @Test
  public void noMatchesAfterCutoffDoesNotScanOrReadOlderArchive() throws Exception {
    var storage = new Storage();
    storage.add(FIRST.minusYears(1), "Red bull 250 г 113 ккал");
    var page = new FoodHistory.Search(storage, catalog, catalog.get("Red bull")).nextPage();
    assertTrue(page.done());
    assertTrue(page.hits().isEmpty());
    assertTrue(storage.reads.isEmpty());
    assertFalse(storage.folders.contains(NoteIndex.ROOT + "/2025"));
  }

  @Test
  public void progressUpdatesFormImmediatelyAndCompletionDoesNotDuplicateIt() throws Exception {
    var storage = new Storage();
    for (int i = 0; i < 30; i++) storage.add(FIRST.plusDays(i), "Мандарин 100 г 38 ккал");
    var form = new FoodHistory.Form(catalog.get("Мандарин"), catalog, AppState.Screen.PORTION, 0);
    var search = new FoodHistory.Search(storage, catalog, form.food);
    form.beginPage();
    var first =
        search.nextPage(
            progress -> {
              form.progress(progress);
              assertFalse(form.hits.isEmpty());
              assertTrue(form.loading);
              assertTrue(storage.reads.size() <= FoodHistory.PAGE_SIZE);
            });
    assertEquals(20, form.hits.size());
    form.complete(first);
    assertEquals(20, form.hits.size());
    assertFalse(form.loading);
    assertFalse(form.done);
    form.beginPage();
    var second = search.nextPage(form::progress);
    form.complete(second);
    assertEquals(30, form.hits.size());
    assertTrue(form.done);
    assertEquals(30, new HashSet<>(form.hits).size());
  }

  @Test
  public void partialHitsRemainVisibleOnReadFailureAndRetryDoesNotRepeatThem() throws Exception {
    var storage = new Storage();
    storage.fail = storage.add(FIRST, "Мандарин 50 г 19 ккал");
    storage.add(FIRST.plusDays(1), "Мандарин 100 г 38 ккал");
    var search = new FoodHistory.Search(storage, catalog, catalog.get("Мандарин"));
    var form =
        new FoodHistory.Form(catalog.get("Мандарин"), catalog, AppState.Screen.CATALOG_EDITOR, 0);
    form.beginPage();
    assertThrows(IOException.class, () -> search.nextPage(form::progress));
    form.failed("Ошибка чтения");
    assertEquals(1, form.hits.size());
    assertFalse(form.loading);
    storage.fail = null;
    form.beginPage();
    form.complete(search.nextPage(form::progress));
    assertEquals(2, form.hits.size());
    assertTrue(form.done);
    assertNull(form.error);
    assertEquals(FIRST.plusDays(1), form.hits.get(0).date());
    assertEquals(FIRST, form.hits.get(1).date());
  }

  @Test
  public void cancellingOnFirstProgressDoesNotContinueReadingOlderNotes() throws Exception {
    var storage = new Storage();
    for (int i = 0; i < 30; i++) storage.add(FIRST.plusDays(i), "Red bull 250 г 113 ккал");
    var search = new FoodHistory.Search(storage, catalog, catalog.get("Red bull"));
    assertThrows(CancellationException.class, () -> search.nextPage(progress -> search.cancel()));
    assertEquals(1, storage.reads.size());
  }

  @Test
  public void incrementalHistoryAlsoPublishesCompositeGroupsAndKeepsRecordedEnergy()
      throws Exception {
    var storage = new Storage();
    storage.add(FIRST, "бутерброд:\n  - хлеб 40 г 80 ккал\n  - сыр 20 г 60 ккал");
    var search = new FoodHistory.Search(storage, catalog, catalog.get("ббр"));
    List<FoodHistory.Hit> progress = new ArrayList<>();
    var page = search.nextPage(progress::addAll);
    assertEquals(1, progress.size());
    assertEquals(1, page.hits().size());
    assertEquals(60, progress.get(0).grams(), 0);
    assertEquals(140, progress.get(0).calories(), 0);
    assertFalse(progress.get(0).estimated());
  }
}
