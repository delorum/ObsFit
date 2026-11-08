package ru.obsfit;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.Test;

public class RecipeBlocksTest {
  private final VaultMarkdown.Catalog catalog = Fixtures.catalog().catalog;
  private static final String BLOCK = "бутерброд:\n  - хлеб 40 г 80 ккал\n  - сыр 20 г 60 ккал";

  private String note(String body) {
    return "## Питание\n### Обед\n" + body + "\n\n### Спорт и диета\nСъеденоКалорий:: 0\n";
  }

  @Test
  public void newAndQuickPortionsWriteVisibleLabelsAndRealIngredientWeights() {
    var form = new PortionForm(Fixtures.day(), "Обед", null);
    form.choose(form.original.catalog.get("ббр"));
    var saved = form.save();
    assertTrue(saved.noteText().contains(BLOCK));
    var day = VaultMarkdown.day(saved.noteText(), form.original.catalog);
    assertEquals(1, day.recipes.size());
    assertEquals(2, day.recipes.get(0).entries().size());
    assertEquals(140, day.recipes.get(0).calories(), 0);
    assertEquals(220, day.total, 0);
    assertEquals(
        saved.noteText(), VaultMarkdown.recalculate(saved.noteText(), form.original.catalog));
  }

  @Test
  public void ingredientWeightCanBeEditedWithoutLosingTheGroupOrCountingItsLabel() {
    var source = note(BLOCK + "\nяблоко 100 ккал");
    var first = VaultMarkdown.day(source, catalog).section("Обед").get(0);
    var edited = VaultMarkdown.edit(source, first, "хлеб 20 г 40 ккал", catalog);
    var day = VaultMarkdown.day(edited, catalog);
    assertEquals(200, day.total, 0);
    assertEquals(100, day.recipes.get(0).calories(), 0);
    assertTrue(edited.contains("бутерброд:\n  - хлеб 20 г 40 ккал\n  - сыр 20 г 60 ккал"));
    assertEquals(3, day.entries.size());
  }

  @Test
  public void deletingAndMovingChildrenRemoveOnlyTheFinalEmptyRecipeLabel() {
    String source = note(BLOCK + "\nдругое 30 ккал");
    var first = VaultMarkdown.day(source, catalog).entries.get(0);
    String moved = VaultMarkdown.move(source, first, "Ужин", catalog);
    var day = VaultMarkdown.day(moved, catalog);
    assertEquals(1, day.recipes.size());
    assertEquals(1, day.recipes.get(0).entries().size());
    assertEquals(170, day.total, 0);
    String deleted = VaultMarkdown.edit(moved, day.section("Обед").get(0), null, catalog);
    assertFalse(deleted.contains("бутерброд:"));
    assertTrue(deleted.contains("другое 30 ккал"));
    assertEquals(110, VaultMarkdown.day(deleted, catalog).total, 0);
  }

  @Test
  public void replacingAnIngredientWithARecipeBuildsANestedListWithoutDuplicateRows() {
    var source = note(BLOCK);
    var snapshot = Fixtures.day(source, Fixtures.FOOD);
    var form = new PortionForm(snapshot, "Обед", snapshot.day.entries.get(0));
    form.choose(snapshot.catalog.get("ббр"));
    form.weight = "30";
    String saved = form.save().noteText();
    assertTrue(
        saved.contains(
            "бутерброд:\n"
                + "  - бутерброд:\n"
                + "    - хлеб 20 г 40 ккал\n"
                + "    - сыр 10 г 30 ккал\n"
                + "  - сыр 20 г 60 ккал"));
    var day = VaultMarkdown.day(saved, snapshot.catalog);
    assertEquals(2, day.recipes.size());
    assertEquals(day.recipes.get(0).line(), day.recipes.get(1).parentLine());
    assertEquals(3, day.entries.size());
    assertEquals(130, day.total, 0);
    var after = VaultMarkdown.edit(saved, day.entries.get(0), null, snapshot.catalog);
    var afterDay = VaultMarkdown.day(after, snapshot.catalog);
    after = VaultMarkdown.edit(after, afterDay.entries.get(0), null, snapshot.catalog);
    assertEquals(1, VaultMarkdown.day(after, snapshot.catalog).recipes.size());
  }

  @Test
  public void repeatedBlocksAreDistinctFactsAndDoNotTriggerAlternativeRecipeInferences() {
    var other =
        VaultMarkdown.catalog(
            Fixtures.FOOD.replace(
                "<!-- food-table:end -->",
                "| другой бутерброд | хл 10 г<br>сыр 10 г | | |\n<!-- food-table:end -->"));
    var day = VaultMarkdown.day(note(BLOCK + "\n\n" + BLOCK), other);
    var occurrences = new FoodOccurrences(other).find(day);
    var hits = occurrences.stream().filter(o -> o.food() == other.get("ббр")).toList();
    assertEquals(2, hits.size());
    assertTrue(hits.stream().noneMatch(FoodOccurrences.Occurrence::estimated));
    assertTrue(occurrences.stream().noneMatch(o -> o.food() == other.get("другой бутерброд")));
    assertEquals(2, occurrences.stream().filter(o -> o.food() == other.get("хл")).count());
  }

  @Test
  public void markedRecipeAndUnmarkedMatchingMealCanBothAppearWithoutSharingIngredients() {
    var day = VaultMarkdown.day(note(BLOCK + "\n\nхл 20 г 40 ккал\nсыр 10 г 30 ккал"), catalog);
    var hits =
        new FoodOccurrences(catalog)
            .find(day).stream().filter(o -> o.food() == catalog.get("ббр")).toList();
    assertEquals(2, hits.size());
    assertEquals(1, hits.stream().filter(FoodOccurrences.Occurrence::estimated).count());
    assertEquals(210, hits.stream().mapToDouble(FoodOccurrences.Occurrence::calories).sum(), 0);
  }

  @Test
  public void protectedTextBlankLinesAndUnindentedFoodDefineGroupBoundaries() {
    String source =
        "```\n"
            + BLOCK
            + "\n```\n> бутерброд:\n>   - хлеб 40 г 80 ккал\n"
            + BLOCK
            + "\nсыр 10 г 30 ккал\n\nбутерброд:\n\nхлеб 40 г 80 ккал\n";
    var day = VaultMarkdown.day(source, catalog);
    assertEquals(
        2, day.recipes.size()); // A known but empty label is retained as an editable placeholder.
    assertEquals(2, day.recipes.get(0).entries().size());
    assertTrue(day.recipes.get(1).entries().isEmpty());
    assertEquals(250, day.total, 0);
  }

  @Test
  public void indentedMarkdownHeadingsEndingInColonAreNotRecipeLabels() {
    var day = VaultMarkdown.day("  ### Обед:\n  хлеб 40 г 80 ккал\n", catalog);
    assertTrue(day.recipes.isEmpty());
    assertEquals(80, day.total, 0);
  }

  @Test
  public void aliasAndDeletedCatalogRecipeLabelsKeepVisibleGroups() {
    var day = VaultMarkdown.day(note(BLOCK.replace("бутерброд:", "ббр:")), catalog);
    assertEquals("бутерброд", day.recipes.get(0).name());
    var withoutRecipe =
        VaultMarkdown.catalog(
            Fixtures.FOOD.replace(
                "| бутерброд<br>ббр | хл 40 г<br>сыр 20 г | 233.33 | 60 |\n", ""));
    var unknown = VaultMarkdown.day(note(BLOCK), withoutRecipe);
    assertEquals("бутерброд", unknown.recipes.get(0).name());
    assertNull(unknown.recipes.get(0).food());
    assertEquals(140, unknown.total, 0);
  }

  @Test
  public void historyAndFrequencyUseTwoMarkedPortionsWithTheirOwnWeights() throws Exception {
    var storage = new Fixtures.MemoryStorage();
    storage.data.put(
        Fixtures.PATH,
        note(BLOCK + "\n\n" + BLOCK.replace("40 г 80", "20 г 40").replace("20 г 60", "10 г 30")));
    var history = new FoodHistory.Search(storage, catalog, catalog.get("ббр")).nextPage();
    assertEquals(2, history.hits().size());
    assertEquals(30, history.hits().get(0).grams(), 0);
    assertEquals(70, history.hits().get(0).calories(), 0);
    assertFalse(history.hits().get(0).estimated());
    var usage =
        new VaultRepository(storage, new Fixtures.MemoryBackups())
            .rebuildMealUsage(LocalDate.of(2026, 11, 8), catalog);
    assertEquals(2, usage.frequency(catalog.get("ббр"), "Обед", LocalDate.of(2026, 11, 8)));
  }
}
