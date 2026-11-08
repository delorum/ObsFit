package ru.obsfit;

import static org.junit.Assert.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import org.junit.Test;

public class MealUsageTest {
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);
  private final VaultMarkdown.Catalog catalog = Fixtures.catalog().catalog;

  private void day(MealUsage usage, LocalDate date, String body) {
    usage.replaceDay(
        date, new FoodOccurrences(catalog).find(VaultMarkdown.day(body, catalog).entries), TODAY);
  }

  private long count(MealUsage usage, String food, String section) {
    return usage.frequency(catalog.get(food), section, TODAY);
  }

  @Test
  public void groupsMealsSeparatelyAndAllFourSnacksIncludingLegacyTogether() {
    var usage = new MealUsage();
    day(
        usage,
        TODAY,
        "## Заметки\nхл 40\n## Питание\n### Завтрак\nхл 40\nхлеб 80\n"
            + "### Обед\nсыр 20\n### Второй обед\nхл 20\n### Ужин\nсыр 40\n"
            + "### Перекус 1\nхл 10\n### Перекус 2\nхл 10\n### Перекус 3\nхл 10\n"
            + "### Перекус 4\nхл 10\n### Перекусы\nхл 10\n");
    assertEquals(2, count(usage, "хлеб", "Завтрак"));
    assertEquals(0, count(usage, "хлеб", "Обед"));
    assertEquals(1, count(usage, "сыр", "Обед"));
    assertEquals(1, count(usage, "хлеб", "Второй обед"));
    assertEquals(1, count(usage, "сыр", "Ужин"));
    assertEquals(1, count(usage, "хлеб", "Заметки"));
    for (String section : List.of("Перекус 1", "Перекус 2", "Перекус 3", "Перекус 4", "Перекусы"))
      assertEquals(5, count(usage, "хлеб", section));
  }

  @Test
  public void includesTodayAndThirteenthPreviousDayButNotFutureOrFourteenthPreviousDay() {
    var usage = new MealUsage();
    for (int offset : List.of(-14, -13, 0, 1))
      day(usage, TODAY.plusDays(offset), "### Обед\nхл 40\n");
    assertEquals(2, count(usage, "хлеб", "Обед"));
    assertEquals(1, usage.frequency(catalog.get("хлеб"), "Обед", TODAY.plusDays(1)));
    assertEquals(0, usage.frequency(catalog.get("хлеб"), "Обед", TODAY.plusDays(14)));
  }

  @Test
  public void countsOccurrencesNotWeightAndReplacesEditedMovedDeletedDaysWithoutDuplicates() {
    var usage = new MealUsage();
    String body = "### Завтрак\nхл 1\nхл 999\nкофе 80 ккал\n100 ккал\n";
    day(usage, TODAY, body);
    day(usage, TODAY, body);
    assertEquals(2, count(usage, "хлеб", "Завтрак"));
    day(usage, TODAY.minusDays(1), "### Обед\nхл 40\n");
    day(usage, TODAY, "### Ужин\nхл 25\n");
    assertEquals(0, count(usage, "хлеб", "Завтрак"));
    assertEquals(1, count(usage, "хлеб", "Ужин"));
    day(usage, TODAY, "");
    assertEquals(0, count(usage, "хлеб", "Ужин"));
    assertEquals(1, count(usage, "хлеб", "Обед"));
  }

  @Test
  public void countsZeroCaloriesIgnoresManualCaloriesAndProtectedMarkdown() {
    var zeroCatalog = VaultMarkdown.catalog(Fixtures.FOOD.replace("200 | 40", "0 | 40"));
    var usage = new MealUsage();
    usage.replaceDay(
        TODAY,
        new FoodOccurrences(zeroCatalog)
            .find(
                VaultMarkdown.day(
                        "### Обед\nхл 40 г 0 ккал\nхлеб 80 ккал\n"
                            + "```\nхл 40\n```\n> хл 40\n<!-- хл 40 -->\n",
                        zeroCatalog)
                    .entries),
        TODAY);
    assertEquals(1, usage.frequency(zeroCatalog.get("хлеб"), "Обед", TODAY));
  }

  @Test
  public void renameKeepsFrequencyThroughOldNameAliasWithoutDoubleCountingAliases() {
    var usage = new MealUsage();
    day(usage, TODAY, "### Обед\nхл 40\n");
    var renamed = new VaultMarkdown.Food("новый хлеб", List.of("хлеб", "ХЛЕБ", "хл"), "", 200, 40);
    assertEquals(1, usage.frequency(renamed, "Обед", TODAY));
    var renamedCatalog =
        VaultMarkdown.catalog(Fixtures.FOOD.replace("хлеб<br>хл", "новый хлеб<br>хлеб<br>хл"));
    usage.replaceDay(
        TODAY.minusDays(1),
        new FoodOccurrences(renamedCatalog)
            .find(VaultMarkdown.day("### Обед\nновый хлеб 40\n", renamedCatalog).entries),
        TODAY);
    assertEquals(2, usage.frequency(renamed, "Обед", TODAY));
  }

  @Test
  public void persistsRollingAggregateAndExpiresOldDataRatherThanGrowingAnEventLog() {
    var usage = new MealUsage();
    for (int i = 100; i >= 0; i--) {
      LocalDate date = TODAY.minusDays(i);
      for (int retry = 0; retry < 5; retry++)
        usage.replaceDay(
            date,
            new FoodOccurrences(catalog)
                .find(VaultMarkdown.day("### Перекус 2\nхл 40\n", catalog).entries),
            date);
    }
    usage.markRebuilt(TODAY);
    String encoded = usage.encode(TODAY);
    assertEquals(16, encoded.lines().count()); // Version, rebuild date, fourteen daily counts.
    var restored = MealUsage.decode(encoded, TODAY);
    assertTrue(restored.rebuilt());
    assertEquals(14, count(restored, "хлеб", "Перекус 4"));
    assertEquals(encoded, restored.encode(TODAY));
    assertEquals(
        0,
        MealUsage.decode(encoded, TODAY.plusDays(14))
            .frequency(catalog.get("хлеб"), "Перекус 1", TODAY.plusDays(14)));
    assertEquals(2, restored.encode(TODAY.plusDays(14)).lines().count());
  }

  @Test
  public void malformedOrUnknownCacheIsDiscardedWithoutPreventingUse() {
    var usage = new MealUsage();
    day(usage, TODAY, "### Обед\nхл 40\n");
    usage.markRebuilt(TODAY);
    for (String encoded :
        Arrays.asList(
            null,
            "",
            "future-version\n-\n",
            "meal-usage-v2\nbad-date\n",
            usage.encode(TODAY) + "invalid\n",
            usage.encode(TODAY).replace("\t1\n", "\t-1\n"))) {
      var decoded = MealUsage.decode(encoded, TODAY);
      assertFalse(decoded.rebuilt());
      assertEquals(0, count(decoded, "хлеб", "Обед"));
    }
  }

  @Test
  public void sortingUsesDestinationFrequencyWithoutFilteringAndPreservesExactSearchPriority() {
    var usage = new MealUsage();
    day(usage, TODAY, "### Завтрак\nхл 40\nхл 40\n### Обед\nсыр 20\nсыр 20\n");
    assertEquals(
        "хлеб",
        FoodSearch.find(catalog.foods, "", f -> usage.frequency(f, "Завтрак", TODAY)).get(0).name);
    assertEquals(
        "сыр",
        FoodSearch.find(catalog.foods, "", f -> usage.frequency(f, "Обед", TODAY)).get(0).name);
    assertEquals(
        catalog.foods.size(),
        FoodSearch.find(catalog.foods, "", f -> usage.frequency(f, "Обед", TODAY)).size());
    assertEquals(
        "хлеб",
        FoodSearch.find(catalog.foods, "хл", f -> usage.frequency(f, "Обед", TODAY)).get(0).name);
    var foods =
        List.of(
            new VaultMarkdown.Food("сыр", List.of(), "", 100, 100),
            new VaultMarkdown.Food("сырный суп", List.of(), "", 100, 100));
    assertEquals(
        "сыр", FoodSearch.find(foods, "сыр", f -> f.name.equals("сыр") ? 0 : 100).get(0).name);
  }

  @Test
  public void expandedRecipesAlsoCountTheParentForFrequency() {
    var form = new PortionForm(Fixtures.day(), "Обед", null);
    form.choose(form.original.catalog.get("ббр"));
    var saved = VaultMarkdown.day(form.save().noteText(), catalog);
    var usage = new MealUsage();
    usage.replaceDay(TODAY, new FoodOccurrences(catalog).find(saved.entries), TODAY);
    assertEquals(1, count(usage, "хлеб", "Обед"));
    assertEquals(1, count(usage, "сыр", "Обед"));
    assertEquals(1, count(usage, "бутерброд", "Обед"));
  }

  private static final class Storage implements VaultStorage {
    final Fixtures.MemoryStorage memory = new Fixtures.MemoryStorage();
    final List<String> reads = new ArrayList<>(), directories = new ArrayList<>();
    String fail;

    Storage() {
      memory.data.clear();
    }

    String add(LocalDate date, String body) {
      String path = DiaryLayout.weekFolder(date) + "/" + date + "-день.md";
      memory.data.put(path, body);
      return path;
    }

    public void validateRoot() {}

    public void requireDirectory(String path) {}

    public List<Entry> children(String path) {
      directories.add(path);
      return memory.children(path);
    }

    public String read(String path) throws IOException {
      reads.add(path);
      if (path.equals(fail)) throw new IOException("Provider failure");
      return memory.read(path);
    }

    public void write(String path, String text) {
      fail("Stats must not write vault files");
    }
  }

  @Test
  public void rebuildReadsOnlyFourteenCalendarDaysRegardlessOfArchiveSizeOrFutureNotes()
      throws Exception {
    var storage = new Storage();
    for (int i = -800; i < 40; i++) storage.add(TODAY.plusDays(i), "### Обед\nхл 40\n");
    var repo = new VaultRepository(storage, new Fixtures.MemoryBackups());
    var usage = repo.rebuildMealUsage(TODAY, catalog);
    assertTrue(usage.rebuilt());
    assertEquals(14, count(usage, "хлеб", "Обед"));
    assertEquals(14, storage.reads.size());
    assertFalse(storage.directories.contains(NoteIndex.ROOT + "/2025"));
  }

  @Test
  public void sparseWindowDoesNotReadOrDescendIntoOldArchiveAndFailedRebuildCanBeRetried()
      throws Exception {
    var storage = new Storage();
    storage.add(TODAY.minusYears(1), "### Обед\nсыр 20\n");
    String path = storage.add(TODAY.minusDays(3), "### Обед\nхл 40\n");
    var repo = new VaultRepository(storage, new Fixtures.MemoryBackups());
    var old = repo.rebuildMealUsage(TODAY, catalog);
    assertEquals(1, storage.reads.size());
    assertFalse(storage.directories.contains(NoteIndex.ROOT + "/2025"));
    storage.memory.data.put(path, "### Обед\nсыр 20\n");
    storage.fail = path;
    assertThrows(IOException.class, () -> repo.rebuildMealUsage(TODAY, catalog));
    assertEquals(1, count(old, "хлеб", "Обед"));
    storage.fail = null;
    var rebuilt = repo.rebuildMealUsage(TODAY, catalog);
    assertEquals(0, count(rebuilt, "хлеб", "Обед"));
    assertEquals(1, count(rebuilt, "сыр", "Обед"));
    storage.memory.data.remove(path);
    assertEquals(0, count(repo.rebuildMealUsage(TODAY, catalog), "сыр", "Обед"));
  }
}
