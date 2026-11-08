package ru.obsfit;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.Test;

public class RecipeRecognitionTest {
  private final VaultMarkdown.Catalog catalog = Fixtures.catalog().catalog;
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

  private List<FoodOccurrences.Occurrence> recipe(String text) {
    return new FoodOccurrences(catalog)
        .find(VaultMarkdown.day(text, catalog).entries).stream()
            .filter(hit -> hit.food() == catalog.get("ббр"))
            .toList();
  }

  @Test
  public void
      recognizesAllIngredientsIgnoringProportionsAndExtraFoodWithoutChangingDiaryCalories() {
    String text = "### Обед\nхл 5 г 10 ккал\nср 100 г 300 ккал\nпирог 200 г 400 ккал\n";
    var day = VaultMarkdown.day(text, catalog);
    var hit = recipe(text).get(0);
    assertEquals("Обед", hit.section());
    assertEquals(105, hit.grams(), 0);
    assertEquals(310, hit.calories(), 1e-9);
    assertTrue(hit.estimated());
    assertEquals(710, day.total, 0);
    assertEquals(text, day.source);
    assertEquals(1, recipe(text).size());
  }

  @Test
  public void neverCombinesIngredientsAcrossMealsOrDifferentSnacks() {
    assertTrue(recipe("### Завтрак\nхл 40\n### Обед\nсыр 20\n").isEmpty());
    assertTrue(recipe("### Перекус 1\nхл 40\n### Перекус 2\nсыр 20\n").isEmpty());
    assertTrue(recipe("### Обед\nхл 40\n").isEmpty());
    assertTrue(recipe("### Обед\nхл 0 г 0 ккал\nсыр 20\n").isEmpty());
  }

  @Test
  public void mergesRepeatedIngredientRowsAndFindsOnlyOneRecipePerMeal() {
    var hits =
        recipe(
            "### Обед\nхл 20 г 40 ккал\nхл 10 г 20 ккал\n"
                + "ср 5 г 15 ккал\nсыр 15 г 45 ккал\n### Ужин\nхл 40\nсыр 20\n");
    assertEquals(2, hits.size());
    assertEquals(50, hits.get(0).grams(), 0);
    assertEquals(120, hits.get(0).calories(), 1e-9);
    assertEquals("Ужин", hits.get(1).section());
  }

  @Test
  public void respectsProtectedMarkdownAndDoesNotTreatManualCaloriesAsAnIngredient() {
    for (String cheese : List.of("сыр 60 ккал", "```\nсыр 20\n```", "> сыр 20", "<!-- сыр 20 -->"))
      assertTrue(recipe("### Обед\nхл 40\n" + cheese + "\n").isEmpty());
  }

  @Test
  public void preservesStoredIngredientCaloriesAfterCatalogEnergyChanges() {
    var changed = VaultMarkdown.catalog(Fixtures.FOOD.replace("200 | 40", "400 | 40"));
    var hits =
        new FoodOccurrences(changed, List.of(changed.get("ббр")))
            .find(
                VaultMarkdown.day("### Обед\nхл 40 г 80 ккал\nсыр 20 г 60 ккал\n", changed)
                    .entries);
    var hit = hits.stream().filter(h -> h.food() == changed.get("ббр")).findFirst().orElseThrow();
    assertEquals(140, hit.calories(), 1e-9);
    assertEquals(60, hit.grams(), 0);
  }

  @Test
  public void explicitRecipeRowsAreNotDuplicatedByMatchingLeavesInTheSameMeal() {
    var hits = recipe("### Обед\nбутерброд 30 г 70 ккал\nббр 60 г 140 ккал\nхл 40\nсыр 20\n");
    assertEquals(2, hits.size());
    assertFalse(hits.get(0).estimated());
    assertEquals(30, hits.get(0).grams(), 0);
    assertEquals(60, hits.get(1).grams(), 0);
  }

  @Test
  public void flattensNestedRecipesAndDuplicateLeavesAndAllowsAllOverlappingRecipes() {
    var nested =
        VaultMarkdown.catalog(
            Fixtures.FOOD.replace(
                "<!-- food-table:end -->",
                "| тарелка<br>тр | ббр 30 г<br>хл 10 г | | |\n"
                    + "| другой бутерброд | хл 10 г<br>сыр 5 г | | |\n<!-- food-table:end -->"));
    var hits =
        new FoodOccurrences(nested)
            .find(
                VaultMarkdown.day("### Обед\nхл 30 г 60 ккал\nср 10 г 30 ккал\n", nested).entries);
    for (String name : List.of("тр", "ббр", "другой бутерброд")) {
      var found = hits.stream().filter(hit -> hit.food() == nested.get(name)).toList();
      assertEquals(1, found.size());
      assertEquals(40, found.get(0).grams(), 0);
      assertEquals(90, found.get(0).calories(), 1e-9);
    }
  }

  @Test
  public void explicitNestedIngredientsCanSupplyLeavesForAnotherRecipe() {
    var nested =
        VaultMarkdown.catalog(
            Fixtures.FOOD.replace(
                "<!-- food-table:end -->",
                "| тарелка | ббр 30 г<br>хл 10 г | | |\n<!-- food-table:end -->"));
    var hits =
        new FoodOccurrences(nested)
            .find(VaultMarkdown.day("### Обед\nббр 60 г 140 ккал\n", nested).entries);
    var hit =
        hits.stream().filter(h -> h.food() == nested.get("тарелка")).findFirst().orElseThrow();
    assertEquals(60, hit.grams(), 1e-9);
    assertEquals(140, hit.calories(), 1e-9);
    assertTrue(hit.estimated());
  }

  @Test
  public void zeroCalorieIngredientsAndRecipesKeepFiniteWeightAndEnergy() {
    var zero =
        VaultMarkdown.catalog(
            Fixtures.FOOD.replace("200 | 40", "0 | 40").replace("300 | 20", "0 | 20"));
    var hits =
        new FoodOccurrences(zero, List.of(zero.get("ббр")))
            .find(VaultMarkdown.day("### Обед\nхл 40 г 0 ккал\nсыр 20 г 0 ккал\n", zero).entries);
    var hit = hits.stream().filter(h -> h.food() == zero.get("ббр")).findFirst().orElseThrow();
    assertEquals(60, hit.grams(), 0);
    assertEquals(0, hit.calories(), 0);
  }

  @Test
  public void frequencyCountsInferredRecipesAfterSaveAndRemovalAndGroupsSnacksOnlyAfterDetection() {
    var usage = new MealUsage();
    var matcher = new FoodOccurrences(catalog);
    String text =
        "### Перекус 1\nхл 40\n### Перекус 2\nсыр 20\n" + "### Перекус 3\nхл 40\nсыр 20\n";
    var hits = matcher.find(VaultMarkdown.day(text, catalog).entries);
    usage.replaceDay(TODAY, hits, TODAY);
    usage.replaceDay(TODAY, hits, TODAY);
    assertEquals(1, usage.frequency(catalog.get("ббр"), "Перекус 4", TODAY));
    assertEquals(2, usage.frequency(catalog.get("хл"), "Перекус 1", TODAY));
    usage.replaceDay(
        TODAY,
        matcher.find(
            VaultMarkdown.day(text.replace("### Перекус 3\nхл 40\nсыр 20\n", ""), catalog).entries),
        TODAY);
    assertEquals(0, usage.frequency(catalog.get("ббр"), "Перекус 4", TODAY));
  }

  @Test
  public void refreshRebuildsRecipeFrequencyForFourteenDaysAndOldCacheIsInvalidated()
      throws Exception {
    var storage = new Fixtures.MemoryStorage();
    storage.data.clear();
    for (int i = -15; i <= 1; i++) {
      var date = TODAY.plusDays(i);
      storage.data.put(
          DiaryLayout.weekFolder(date) + "/" + date + "-день.md", "### Обед\nхл 40\nсыр 20\n");
    }
    var repo = new VaultRepository(storage, new Fixtures.MemoryBackups());
    var usage = repo.rebuildMealUsage(TODAY, catalog);
    assertEquals(14, usage.frequency(catalog.get("ббр"), "Обед", TODAY));
    assertEquals(14, storage.reads);
    assertEquals(0, storage.writes);
    var restored = MealUsage.decode(usage.encode(TODAY), TODAY);
    assertEquals(14, restored.frequency(catalog.get("ббр"), "Обед", TODAY));
    assertFalse(
        MealUsage.decode(usage.encode(TODAY).replace("meal-usage-v3", "meal-usage-v1"), TODAY)
            .rebuilt());
  }

  @Test
  public void historyPaginatesInferredMealsAndReturnsActualWeightsAndStoredCalories()
      throws Exception {
    var storage = new Fixtures.MemoryStorage();
    storage.data.clear();
    for (int i = 0; i < 25; i++) {
      var date = TODAY.minusDays(i);
      storage.data.put(
          DiaryLayout.weekFolder(date) + "/" + date + "-день.md",
          "### Обед\nхл 40 г 80 ккал\nсыр 20 г 60 ккал\n"
              + "### Ужин\nхл 20 г 40 ккал\nсыр 10 г 30 ккал\n");
    }
    var search = new FoodHistory.Search(storage, catalog, catalog.get("ббр"));
    var first = search.nextPage();
    assertEquals(20, first.hits().size());
    assertEquals(10, storage.reads);
    assertEquals(TODAY, first.hits().get(0).date());
    assertEquals("Ужин", first.hits().get(0).section());
    assertEquals(30, first.hits().get(0).grams(), 0);
    assertEquals(70, first.hits().get(0).calories(), 1e-9);
    assertTrue(first.hits().get(0).estimated());
    assertEquals(20, search.nextPage().hits().size());
    var last = search.nextPage();
    assertEquals(10, last.hits().size());
    assertTrue(last.done());
    assertEquals(25, storage.reads);
    assertEquals(0, storage.writes);
  }
}
