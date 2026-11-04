package ru.obsfit;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class NestedRecipeTest {
  private static final String NESTED =
      Fixtures.FOOD.replace(
          "| хлеб<br>хл |",
          "| тарелка<br>тр | ббр 30 г<br>хл 10 г | | |\n"
              + "| набор | тр 80 г<br>сыр 20 г | | |\n"
              + "| хлеб<br>хл |");

  @Test
  public void resolvesForwardReferencesAndAliasesAcrossMultipleLevels() {
    var catalog = VaultMarkdown.catalog(NESTED);
    assertEquals(40, catalog.get("тр").weight, 0);
    assertEquals(225, catalog.get("тр").kcal100, .000001);
    assertEquals(100, catalog.get("набор").weight, 0);
    assertEquals(240, catalog.get("набор").kcal100, .000001);
    assertEquals("ббр 30 г<br>хл 10 г", catalog.get("тр").ingredients);
  }

  @Test
  public void selectionFlattensNestedDishAndMergesRepeatedLeaves() {
    var catalog = VaultMarkdown.catalog(NESTED);
    var items = RecipeIngredients.add(catalog, List.of(), "тр", 20, null);
    items = RecipeIngredients.add(catalog, items, "тарелка", 10, null);
    items = RecipeIngredients.add(catalog, items, "хл", 10, null);
    assertEquals("хлеб 32.5 г<br>сыр 7.5 г", RecipeIngredients.toMarkdown(items));
    assertEquals(items, RecipeIngredients.parse(catalog, RecipeIngredients.toMarkdown(items)));
    assertEquals(218.75, RecipeIngredients.nutrition(catalog, items).kcal100(), .000001);
    String saved =
        VaultMarkdown.putFood(
            catalog,
            null,
            new VaultMarkdown.Food(
                "новый набор", List.of(), RecipeIngredients.toMarkdown(items), 0, 0),
            Fixtures.REPLACEMENTS);
    assertEquals(
        "хлеб 32.5 г<br>сыр 7.5 г", VaultMarkdown.catalog(saved).get("новый набор").ingredients);
  }

  @Test
  public void rejectsSelfReferenceAndCyclesThroughAliases() {
    String[] rows = {
      "| первое<br>п | п 10 г | | |",
      "| первое<br>п | вт 10 г | | |\n| второе<br>вт | третье 20 г | | |\n| третье | п 30 г | | |"
    };
    for (String row : rows) {
      var error =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  VaultMarkdown.catalog(
                      "<!-- food-table:start -->\n"
                          + "| Еда | Состав | Ккал | Вес |\n"
                          + "| --- | --- | --- | --- |\n"
                          + row
                          + "\n<!-- food-table:end -->"));
      assertTrue(error.getMessage().contains("Циклический состав"));
      assertTrue(error.getMessage().contains("первое"));
    }
  }

  @Test
  public void pickerExcludesEveryTransitiveDependentAndSaveRejectsInjectedCycle() {
    var catalog = VaultMarkdown.catalog(NESTED);
    assertFalse(RecipeIngredients.available(catalog, catalog.get("набор"), "хлеб"));
    assertFalse(RecipeIngredients.available(catalog, catalog.get("тр"), "бутерброд"));
    assertTrue(RecipeIngredients.available(catalog, catalog.get("хлеб"), "тарелка"));
    var form =
        new CatalogForm(
            new VaultRepository.CatalogSnapshot(NESTED, Fixtures.REPLACEMENTS),
            catalog.get("бутерброд"));
    assertThrows(IllegalArgumentException.class, () -> form.add(catalog.get("набор"), 10));
    form.ingredients = List.of(new RecipeIngredients.Item("набор", 10));
    assertThrows(IllegalArgumentException.class, form::save);
  }

  @Test
  public void renameUpdatesReferencesDeletionIsBlockedAndNutritionChangesPropagate() {
    var catalog = VaultMarkdown.catalog(NESTED);
    String renamed =
        VaultMarkdown.putFood(
            catalog,
            "бутерброд",
            new VaultMarkdown.Food("сэндвич", List.of(), "хл 40 г<br>сыр 20 г", 0, 0),
            Fixtures.REPLACEMENTS);
    var updated = VaultMarkdown.catalog(renamed);
    assertEquals("сэндвич 30 г<br>хл 10 г", updated.get("тр").ingredients);
    assertEquals(240, updated.get("набор").kcal100, .000001);
    assertThrows(
        IllegalArgumentException.class, () -> VaultMarkdown.deleteFood(updated, "сэндвич"));
    String changed =
        VaultMarkdown.putFood(
            updated,
            "хлеб",
            new VaultMarkdown.Food("хлеб", List.of("хл"), "", 400, 999),
            Fixtures.REPLACEMENTS);
    assertEquals(360, VaultMarkdown.catalog(changed).get("набор").kcal100, .000001);
    assertEquals(100, VaultMarkdown.catalog(changed).get("набор").weight, 0);
  }

  @Test
  public void legacyCatalogDraftRemainsReadableAndNewPortionDraftIsFlattened() {
    var snapshot = Fixtures.day(Fixtures.DAY, NESTED);
    var portion = new PortionForm(snapshot, "Обед", null);
    portion.startNew();
    portion.name = "новый набор";
    portion.composition.add(snapshot.catalog.get("набор"), 50);
    portion.recalculateRecipe();
    var restored = PortionForm.restore(snapshot, portion.draft());
    assertEquals("хлеб 30 г<br>сыр 20 г", restored.save().newFood().ingredients);
    assertEquals(120, restored.total(), 0);
    assertTrue(restored.save().noteText().contains("хлеб 30 г 60 ккал"));
    assertTrue(restored.save().noteText().contains("сыр 20 г 60 ккал"));
    var source = new VaultRepository.CatalogSnapshot(NESTED, Fixtures.REPLACEMENTS);
    var form = new CatalogForm(source, source.catalog.get("тарелка"));
    var recovered = CatalogForm.restore(source, form.draft());
    assertEquals("бутерброд 30 г<br>хлеб 10 г", recovered.input().ingredients);
    assertEquals(225, VaultMarkdown.catalog(recovered.save()).get("тарелка").kcal100, .000001);
  }
}
