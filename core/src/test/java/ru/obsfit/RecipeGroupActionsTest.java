package ru.obsfit;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.Test;

public class RecipeGroupActionsTest {
  private static final String FOOD =
      Fixtures.FOOD.replace(
          "<!-- food-table:end -->",
          "| вода | | 0 | 100 |\n"
              + "| тарелка | ббр 60 г<br>вода 100 г | | |\n"
              + "| набор | тарелка 160 г<br>сыр 10 г | | |\n"
              + "<!-- food-table:end -->");
  private static final String BLOCK =
      "набор:\n"
          + "  - тарелка:\n"
          + "    - бутерброд:\n"
          + "      - хлеб 40 г 80 ккал\n"
          + "      - сыр 20 г 60 ккал\n"
          + "    - вода 100 г 0 ккал\n"
          + "  - сыр 10 г 30 ккал";
  private static final String SOURCE =
      "## Заметки\nдругая еда 20 ккал\n\n## Питание\n### Завтрак\n"
          + BLOCK
          + "\n\n"
          + "### Ужин\n"
          + "хлеб 10 г 20 ккал\n\n"
          + "### Спорт и диета\n"
          + "СуточнаяНормаКалорий:: 2150\n"
          + "Вес:: 103\n"
          + "СъеденоКалорий:: 210\n";
  private final VaultMarkdown.Catalog catalog = VaultMarkdown.catalog(FOOD);

  private RecipeBlocks.Group group(String source, String name) {
    return VaultMarkdown.day(source, catalog).recipes.stream()
        .filter(g -> g.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  @Test
  public void deletesEntireTopLevelSubtreeAndKeepsMealsManualCaloriesAndHealthFields() {
    String result = RecipeBlocks.delete(SOURCE, group(SOURCE, "набор"), catalog);
    var day = VaultMarkdown.day(result, catalog);
    assertTrue(day.recipes.isEmpty());
    assertEquals(2, day.entries.size());
    assertEquals(40, day.total, 0);
    assertTrue(result.contains("СъеденоКалорий:: 40"));
    assertTrue(result.contains("### Завтрак"));
    assertTrue(result.contains("Вес:: 103\n"));
    assertTrue(result.contains("СуточнаяНормаКалорий:: 2150"));
    assertTrue(result.contains("другая еда 20 ккал"));
  }

  @Test
  public void deletingNestedDishKeepsRemainingParentAndUpdatesItsWeightAndCalories() {
    String result = RecipeBlocks.delete(SOURCE, group(SOURCE, "бутерброд"), catalog);
    var day = VaultMarkdown.day(result, catalog);
    assertEquals(2, day.recipes.size());
    assertEquals(110, group(result, "набор").grams(), 0);
    assertEquals(30, group(result, "набор").calories(), 0);
    assertEquals(100, group(result, "тарелка").grams(), 0);
    assertEquals(0, group(result, "тарелка").calories(), 0);
    assertEquals(70, day.total, 0);
    assertFalse(result.contains("бутерброд:"));
  }

  @Test
  public void deletingOnlyNestedChildRemovesAllEmptyAncestors() {
    String source =
        "### Завтрак\n"
            + "набор:\n"
            + "  - тарелка:\n"
            + "    - бутерброд:\n"
            + "      - хлеб 40 г 80 ккал\n"
            + "      - сыр 20 г 60 ккал\n\n"
            + "### Ужин\n"
            + "хлеб 10 г 20 ккал\n";
    String result = RecipeBlocks.delete(source, group(source, "бутерброд"), catalog);
    assertTrue(VaultMarkdown.day(result, catalog).recipes.isEmpty());
    assertEquals(20, VaultMarkdown.day(result, catalog).total, 0);
    assertFalse(result.contains("набор:"));
    assertFalse(result.contains("тарелка:"));
  }

  @Test
  public void movingOuterDishPreservesTheWholeTreeAndDailyCalories() {
    var before = VaultMarkdown.day(SOURCE, catalog);
    String result = RecipeBlocks.move(SOURCE, group(SOURCE, "набор"), "Ужин", catalog);
    var after = VaultMarkdown.day(result, catalog);
    assertEquals(before.total, after.total, 0);
    assertTrue(after.section("Завтрак").isEmpty());
    assertEquals(3, after.recipes.size());
    assertEquals(-1, group(result, "набор").parentLine());
    assertEquals(group(result, "набор").line(), group(result, "тарелка").parentLine());
    assertTrue(after.recipes.stream().allMatch(g -> g.section().equals("Ужин")));
    assertTrue(result.contains(BLOCK));
    assertEquals(170, group(result, "набор").calories(), 0);
    assertEquals(190, after.section("Ужин").stream().mapToDouble(e -> e.kcal).sum(), 0);
  }

  @Test
  public void movingNestedDishPromotesItWithRelativeIndentationAndLeavesItsSiblings() {
    String result = RecipeBlocks.move(SOURCE, group(SOURCE, "тарелка"), "Ужин", catalog);
    var after = VaultMarkdown.day(result, catalog);
    assertEquals(210, after.total, 0);
    assertEquals(10, group(result, "набор").grams(), 0);
    var moved = group(result, "тарелка");
    assertEquals(-1, moved.parentLine());
    assertEquals("Ужин", moved.section());
    assertEquals(moved.line(), group(result, "бутерброд").parentLine());
    assertEquals(160, moved.grams(), 0);
    assertTrue(
        result.contains(
            "тарелка:\n"
                + "  - бутерброд:\n"
                + "    - хлеб 40 г 80 ккал\n"
                + "    - сыр 20 г 60 ккал\n"
                + "  - вода 100 г 0 ккал"));
    assertTrue(result.contains("набор:\n  - сыр 10 г 30 ккал"));
  }

  @Test
  public void moveToEarlierSectionAndLegacySnacksPreservesTotalsAndSectionAliases() {
    String earlier = RecipeBlocks.move(SOURCE, group(SOURCE, "бутерброд"), "Заметки", catalog);
    assertEquals("Заметки", group(earlier, "бутерброд").section());
    assertEquals(210, VaultMarkdown.day(earlier, catalog).total, 0);
    String legacy = SOURCE.replace("### Ужин", "### Перекусы");
    String moved = RecipeBlocks.move(legacy, group(legacy, "набор"), "Перекусы", catalog);
    assertTrue(moved.contains("### Перекусы"));
    assertFalse(moved.contains("### Перекус 4"));
    assertEquals("Перекус 4", group(moved, "набор").section());
    assertEquals(210, VaultMarkdown.day(moved, catalog).total, 0);
  }

  @Test
  public void movingTheLastNestedDishCleansItsOldAncestors() {
    String source =
        "### Завтрак\n"
            + "набор:\n"
            + "  - тарелка:\n"
            + "    - бутерброд:\n"
            + "      - хлеб 40 г 80 ккал\n"
            + "      - сыр 20 г 60 ккал\n\n"
            + "### Ужин\n";
    String moved = RecipeBlocks.move(source, group(source, "бутерброд"), "Ужин", catalog);
    var after = VaultMarkdown.day(moved, catalog);
    assertEquals(1, after.recipes.size());
    assertEquals(-1, after.recipes.get(0).parentLine());
    assertEquals("Ужин", after.recipes.get(0).section());
    assertEquals(140, after.total, 0);
    assertFalse(moved.contains("набор:"));
    assertFalse(moved.contains("тарелка:"));
  }

  @Test
  public void repeatedSameNamedPortionsAreIndependent() {
    String source = SOURCE.replace("### Ужин\n", "### Ужин\n" + BLOCK + "\n\n");
    var groups = VaultMarkdown.day(source, catalog).recipes;
    assertEquals(6, groups.size());
    String result = RecipeBlocks.delete(source, groups.get(0), catalog);
    var after = VaultMarkdown.day(result, catalog);
    assertEquals(3, after.recipes.size());
    assertTrue(after.recipes.stream().allMatch(g -> g.section().equals("Ужин")));
    assertEquals(210, after.total, 0);
    assertTrue(result.contains(BLOCK));
  }

  @Test
  public void staleHeaderOrDescendantCannotBeRemovedOrMoved() {
    var original = group(SOURCE, "набор");
    for (String changed :
        List.of(
            SOURCE.replace("хлеб 40 г", "хлеб 35 г"),
            SOURCE.replace("набор:", "другое название:"),
            SOURCE.replace("  - сыр 10 г 30 ккал", "  - сыр 20 г 60 ккал"))) {
      assertThrows(
          IllegalStateException.class, () -> RecipeBlocks.delete(changed, original, catalog));
      assertThrows(
          IllegalStateException.class, () -> RecipeBlocks.move(changed, original, "Ужин", catalog));
    }
  }

  @Test
  public void sameSectionIsNoOpAndUnknownDestinationIsRejected() {
    var original = group(SOURCE, "набор");
    assertEquals(SOURCE, RecipeBlocks.move(SOURCE, original, "Завтрак", catalog));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecipeBlocks.move(SOURCE, original, "Неизвестно", catalog));
  }

  @Test
  public void crlfAndNestedAliasesSurviveRebasing() {
    String source = SOURCE.replace("бутерброд:", "ббр:").replace("\n", "\r\n");
    String moved = RecipeBlocks.move(source, group(source, "бутерброд"), "Ужин", catalog);
    assertFalse(moved.replace("\r\n", "").contains("\n"));
    assertTrue(moved.contains("ббр:\r\n  - хлеб 40 г 80 ккал\r\n  - сыр 20 г 60 ккал"));
    assertEquals(210, VaultMarkdown.day(moved, catalog).total, 0);
  }

  @Test
  public void orphanedCatalogLabelCanStillBeMovedOrDeleted() {
    var without = VaultMarkdown.catalog(VaultMarkdown.deleteFood(catalog, "набор"));
    var root = VaultMarkdown.day(SOURCE, without).recipes.get(0);
    assertNull(root.food());
    String moved = RecipeBlocks.move(SOURCE, root, "Ужин", without);
    assertEquals(210, VaultMarkdown.day(moved, without).total, 0);
    assertTrue(
        VaultMarkdown.day(RecipeBlocks.delete(SOURCE, root, without), without).recipes.isEmpty());
  }

  @Test
  public void repositorySavesOnceWithBackupAndReplacesMealFrequencyWithoutDuplication()
      throws Exception {
    var storage = new Fixtures.MemoryStorage();
    storage.data.put("Еда.md", FOOD);
    storage.data.put(Fixtures.PATH, SOURCE);
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(storage, backups);
    var original = repo.load(Fixtures.day().note);
    var root = original.day.recipes.get(0);
    String updated = RecipeBlocks.move(original.day.source, root, "Ужин", original.catalog);
    repo.save(original, updated, null);
    assertEquals(1, storage.writes);
    assertEquals(List.of(SOURCE), backups.saved);
    assertEquals(FOOD, storage.data.get("Еда.md"));
    var saved = repo.load(original.note);
    var usage = new MealUsage();
    var today = LocalDate.of(2026, 11, 8);
    var matcher = new FoodOccurrences(original.catalog);
    usage.replaceDay(original.note.date, matcher.find(original.day), today);
    usage.replaceDay(saved.note.date, matcher.find(saved.day), today);
    for (String name : List.of("набор", "тарелка", "ббр")) {
      assertEquals(0, usage.frequency(original.catalog.get(name), "Завтрак", today));
      assertEquals(1, usage.frequency(original.catalog.get(name), "Ужин", today));
    }
    storage.data.put(original.note.path, updated + "Внешняя правка\n");
    assertThrows(
        VaultRepository.StaleSnapshot.class,
        () ->
            repo.save(
                saved,
                RecipeBlocks.delete(saved.day.source, saved.day.recipes.get(0), saved.catalog),
                null));
    assertEquals(1, storage.writes);
    assertEquals(1, backups.saved.size());
  }
}
