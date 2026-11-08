package ru.obsfit;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class RecipeExpansionTest {
  private static final String NESTED =
      Fixtures.FOOD.replace(
          "<!-- food-table:end -->",
          "| тарелка<br>тр | ббр 30 г<br>хл 10 г | | |\n"
              + "| набор<br>нб | тр 80 г<br>сыр 20 г | | |\n<!-- food-table:end -->");

  private PortionForm selected(String foodText, String name, String weight) {
    var snapshot = Fixtures.day(Fixtures.DAY, foodText);
    var form = new PortionForm(snapshot, "Обед", null);
    form.choose(snapshot.catalog.get(name));
    form.weight = weight;
    return form;
  }

  @Test
  public void selectedRecipeAddsSeparateLeafRows() {
    var form = selected(Fixtures.FOOD, "ббр", "30");
    var save = form.save();
    var day = VaultMarkdown.day(save.noteText(), form.original.catalog);
    assertNull(save.newFood());
    assertEquals(2, day.section("Обед").size());
    assertTrue(save.noteText().contains("хлеб 20 г 40 ккал"));
    assertTrue(save.noteText().contains("сыр 10 г 30 ккал"));
    assertTrue(save.noteText().contains("бутерброд:\n  - хлеб"));
    assertEquals(70, form.total(), 0);
    assertEquals(150, day.total, 0);
    assertTrue(save.noteText().contains("СъеденоКалорий:: 150"));
  }

  @Test
  public void nestedRecipePreservesBranchesAtActualPortionWeight() {
    var form = selected(NESTED, "нб", "50");
    var save = form.save();
    var meals = VaultMarkdown.day(save.noteText(), form.original.catalog).section("Обед");
    assertEquals(4, meals.size());
    assertEquals(
        30, meals.stream().filter(e -> e.name.equals("хлеб")).mapToDouble(e -> e.weight).sum(), 0);
    assertEquals(
        20, meals.stream().filter(e -> e.name.equals("сыр")).mapToDouble(e -> e.weight).sum(), 0);
    assertEquals(3, VaultMarkdown.day(save.noteText(), form.original.catalog).recipes.size());
    assertEquals(120, meals.stream().mapToDouble(e -> e.kcal).sum(), 0);
    assertEquals(120, form.total(), 0);
    assertTrue(meals.stream().allMatch(e -> e.food.ingredients.isEmpty()));
    assertEquals("тр 80 г<br>сыр 20 г", form.original.catalog.get("нб").ingredients);
  }

  @Test
  public void catalogPickerPreservesNestedRecipeBesideExistingPrimitive() {
    var source = new VaultRepository.CatalogSnapshot(NESTED, Fixtures.REPLACEMENTS);
    var form = new CatalogForm(source, null);
    form.name = "новое блюдо";
    form.add(source.catalog.get("хл"), 10);
    form.add(source.catalog.get("нб"), 50);
    assertTrue(form.input().ingredients.contains("набор:<br>&nbsp;&nbsp;- тарелка:"));
    assertEquals(
        "хлеб 40 г<br>сыр 20 г",
        RecipeIngredients.toMarkdown(RecipeIngredients.flatten(source.catalog, form.ingredients)));
    var food = VaultMarkdown.catalog(form.save()).get("новое блюдо");
    assertEquals(60, food.weight, 0);
    assertEquals(233.3333333, food.kcal100, .000001);
    assertTrue(food.ingredients.contains("бутерброд:"));
  }

  @Test
  public void editingLegacyRecipeReplacesItWithALabelAndIndentedLeaves() {
    String note = Fixtures.DAY.replace("### Завтрак", "### Обед\n- ббр 60 г 140 ккал\n### Завтрак");
    var snapshot = Fixtures.day(note, Fixtures.FOOD);
    var form = new PortionForm(snapshot, "Обед", snapshot.day.section("Обед").get(0));
    form.weight = "30";
    var saved = form.save().noteText();
    assertTrue(saved.contains("бутерброд:\n  - хлеб 20 г 40 ккал\n  - сыр 10 г 30 ккал"));
    assertEquals(2, VaultMarkdown.day(saved, snapshot.catalog).section("Обед").size());
    assertEquals(150, VaultMarkdown.day(saved, snapshot.catalog).total, 0);
    assertFalse(saved.contains("140 ккал"));
  }

  @Test
  public void zeroCalorieIngredientsStayInDiaryAndPreviewMatchesRoundedLeafCalories() {
    String source =
        Fixtures.FOOD.replace(
            "<!-- food-table:end -->",
            "| вода | | 0 | 200 |\n"
                + "| напиток | вода 200 г<br>сыр 20 г | | |\n"
                + "| рис | | 40 | 1 |\n"
                + "| томат | | 40 | 1 |\n"
                + "| микс | рис 1 г<br>томат 1 г | | |\n"
                + "<!-- food-table:end -->");
    var drink = selected(source, "напиток", "110");
    assertEquals(30, drink.total(), 0);
    var rows = VaultMarkdown.day(drink.save().noteText(), drink.original.catalog).section("Обед");
    assertEquals(2, rows.size());
    assertEquals("вода", rows.get(0).name);
    assertEquals(0, rows.get(0).kcal, 0);
    assertEquals(100, rows.get(0).weight, 0);
    var mix = selected(source, "микс", "2");
    assertEquals(0, mix.total(), 0); // две записи по 0.4 ккал округляются отдельно
    assertEquals(
        0,
        VaultMarkdown.day(mix.save().noteText(), mix.original.catalog).section("Обед").stream()
            .mapToDouble(e -> e.kcal)
            .sum(),
        0);
  }

  @Test
  public void fractionalAndTinyWeightsRoundTripWithoutZeroingPositiveIngredients() {
    String source =
        Fixtures.FOOD.replace(
            "<!-- food-table:end -->",
            "| вода | | 0 | 1 |\n"
                + "| маленькое | вода 0.001 г<br>сыр 0.001 г | | |\n"
                + "<!-- food-table:end -->");
    var tiny = selected(source, "маленькое", "0.002");
    String saved = tiny.save().noteText();
    var rows = VaultMarkdown.day(saved, tiny.original.catalog).section("Обед");
    assertEquals(2, rows.size());
    assertTrue(rows.stream().allMatch(e -> e.weight > 0));
    assertEquals(.002, rows.stream().mapToDouble(e -> e.weight).sum(), 1e-12);
    assertEquals(saved, VaultMarkdown.recalculate(saved, tiny.original.catalog));
    var random = new Random(27);
    for (int i = 0; i < 200; i++) {
      var form = selected(NESTED, "набор", Double.toString((1 + random.nextInt(10000)) / 100d));
      var day = VaultMarkdown.day(form.save().noteText(), form.original.catalog);
      assertEquals(form.total(), day.section("Обед").stream().mapToDouble(e -> e.kcal).sum(), 0);
      assertEquals(
          VaultMarkdown.number(form.weight),
          day.section("Обед").stream().mapToDouble(e -> e.weight).sum(),
          .011);
    }
  }
}
