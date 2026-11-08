package ru.obsfit;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.Test;

public class RecipeTreeTest {
  private static final String SOURCE =
      Fixtures.FOOD.replace(
          "<!-- food-table:end -->",
          "| тарелка<br>тр | ббр 30 г<br>хл 10 г | | |\n"
              + "| набор | тр 80 г<br>сыр 20 г | | |\n"
              + "<!-- food-table:end -->");
  private final VaultMarkdown.Catalog catalog = VaultMarkdown.catalog(SOURCE);

  private PortionForm form() {
    var form = new PortionForm(Fixtures.day(Fixtures.DAY, SOURCE), "Обед", null);
    form.choose(form.original.catalog.get("набор"));
    form.changePortionWeight("50");
    return form;
  }

  @Test
  public void portionWritesThreeRecipeLevelsWithEachLeafExactlyOnce() {
    var form = form();
    var day = VaultMarkdown.day(form.save().noteText(), form.original.catalog);
    assertEquals(3, day.recipes.size());
    assertEquals(
        List.of("набор", "тарелка", "бутерброд"),
        day.recipes.stream().map(RecipeBlocks.Group::name).toList());
    assertEquals(4, day.section("Обед").size());
    assertEquals(50, day.recipes.get(0).grams(), 1e-9);
    assertEquals(120, day.recipes.get(0).calories(), 0);
    assertTrue(
        form.save()
            .noteText()
            .contains("  - тарелка:\n    - бутерброд:\n      - хлеб 20 г 40 ккал"));
  }

  @Test
  public void childWeightUpdatesAncestorsWhileCompositeWeightsAreReadOnly() {
    var form = form();
    var composition = form.composition;
    var bread =
        composition.nodes().stream()
            .filter(n -> n.item().name().equals("хлеб"))
            .findFirst()
            .orElseThrow();
    composition.changeWeight(bread.index(), 30);
    form.compositionChanged();
    assertEquals("60", form.weight);
    assertEquals(140, form.total(), 0);
    assertEquals(50, composition.ingredients.get(0).grams(), 1e-9);
    assertThrows(IllegalStateException.class, () -> composition.changeWeight(0, 100));
    var saved = VaultMarkdown.day(form.save().noteText(), form.original.catalog);
    assertEquals(60, saved.recipes.get(0).grams(), 0);
    assertEquals(140, saved.recipes.get(0).calories(), 0);
  }

  @Test
  public void calculatorRejectsCompositeWeightAndAcceptsNestedLeafAndRestoresDraft() {
    var c = new AppController();
    c.day(Fixtures.day(Fixtures.DAY, SOURCE));
    c.portion("Обед", null);
    c.chooseFood(c.state.day.catalog.get("набор"));
    assertThrows(
        IllegalStateException.class,
        () -> c.calculator(CalculatorForm.Target.PORTION_INGREDIENT, 0, null, "80", 0));
    var leaf =
        c.state.portion.composition.nodes().stream()
            .filter(n -> n.item().children().isEmpty())
            .findFirst()
            .orElseThrow();
    c.calculator(CalculatorForm.Target.PORTION_INGREDIENT, leaf.index(), null, "20+10", 0);
    var saved = c.state.save();
    var restored = new AppController();
    restored.restore(saved, List.of(c.state.day.note), c.state.day, null);
    assertEquals(leaf.index(), restored.state.calculator.ingredientIndex);
    assertEquals("20+10", restored.state.calculator.expression);
    assertEquals(c.state.portion.draft(), restored.state.portion.draft());
  }

  @Test
  public void pendingCompositeWeightCannotBeChangedWithCalculatorInEitherEditor() {
    var c = new AppController();
    c.day(Fixtures.day());
    c.portion("Обед", null);
    c.newFood();
    assertThrows(
        IllegalStateException.class,
        () -> c.calculator(CalculatorForm.Target.PORTION_INGREDIENT, -1, "бутерброд", "60", 0));
    assertTrue(c.state.portion.composition.ingredients.isEmpty());
    c.editCatalog(Fixtures.catalog(), null, false);
    assertThrows(
        IllegalStateException.class,
        () -> c.calculator(CalculatorForm.Target.CATALOG_INGREDIENT, -1, "бутерброд", "60", 0));
    assertTrue(c.state.catalogForm.ingredients.isEmpty());
  }

  @Test
  public void catalogTreeRoundTripsCustomChildrenInsteadOfUsingDefaultRecipeProportions() {
    var f =
        new CatalogForm(new VaultRepository.CatalogSnapshot(SOURCE, Fixtures.REPLACEMENTS), null);
    f.name = "новая порция";
    f.add(f.original.catalog.get("ббр"), 60);
    var cheese =
        f.nodes().stream().filter(n -> n.item().name().equals("сыр")).findFirst().orElseThrow();
    f.changeWeight(cheese.index(), 40);
    var text = f.save();
    var updated = VaultMarkdown.catalog(text);
    var food = updated.get(f.name);
    assertTrue(
        food.ingredients.startsWith(
            "бутерброд:<br>&nbsp;&nbsp;- хлеб 40 г<br>&nbsp;&nbsp;- сыр 40 г"));
    assertEquals(80, food.weight, 0);
    assertEquals(250, food.kcal100, 1e-9);
    var recovered = CatalogForm.restore(f.original, f.draft());
    assertEquals(f.input().ingredients, recovered.input().ingredients);
  }

  @Test
  public void deletionAndRenameInspectEveryNestedReferenceAndLeafEnergyStillPropagates() {
    var form =
        new CatalogForm(new VaultRepository.CatalogSnapshot(SOURCE, Fixtures.REPLACEMENTS), null);
    form.name = "мой набор";
    form.add(form.original.catalog.get("набор"), 50);
    var saved = VaultMarkdown.catalog(form.save());
    assertThrows(
        IllegalArgumentException.class, () -> VaultMarkdown.deleteFood(saved, "бутерброд"));
    var renamed =
        VaultMarkdown.catalog(
            VaultMarkdown.putFood(
                saved,
                "бутерброд",
                new VaultMarkdown.Food("сэндвич", List.of(), "хл 40 г<br>сыр 20 г", 0, 0),
                Fixtures.REPLACEMENTS));
    assertTrue(renamed.get("мой набор").ingredients.contains("сэндвич:"));
    var changed =
        VaultMarkdown.catalog(
            VaultMarkdown.putFood(
                renamed,
                "хлеб",
                new VaultMarkdown.Food("хлеб", List.of("хл"), "", 400, 40),
                Fixtures.REPLACEMENTS));
    assertEquals(360, changed.get("мой набор").kcal100, 1e-9);
  }

  @Test
  public void cyclesAndBrokenIndentationAreRejectedInExpandedCatalogCells() {
    for (String bad :
        List.of("бутерброд:", "&nbsp;&nbsp;- хлеб 40 г", "хлеб 40 г<br>&nbsp;&nbsp;- сыр 10 г"))
      assertThrows(IllegalArgumentException.class, () -> RecipeIngredients.parse(catalog, bad));
    String cycle =
        Fixtures.FOOD.replace(
            "| бутерброд<br>ббр | хл 40 г<br>сыр 20 г | 233.33 | 60 |",
            "| бутерброд<br>ббр | бутерброд:<br>&nbsp;&nbsp;- хлеб 40 г | | |");
    assertThrows(IllegalArgumentException.class, () -> VaultMarkdown.catalog(cycle));
  }

  @Test
  public void historyAndUsageIncludeAllNestedFactualNamesWithoutDoubleCalories() throws Exception {
    var form = form();
    var storage = new Fixtures.MemoryStorage();
    storage.data.put("Еда.md", SOURCE);
    storage.data.put(Fixtures.PATH, form.save().noteText());
    for (String name : List.of("набор", "тр", "ббр")) {
      var page =
          new FoodHistory.Search(storage, form.original.catalog, form.original.catalog.get(name))
              .nextPage();
      assertEquals(1, page.hits().size());
      assertFalse(page.hits().get(0).estimated());
      var usage =
          new VaultRepository(storage, new Fixtures.MemoryBackups())
              .rebuildMealUsage(LocalDate.of(2026, 11, 8), form.original.catalog);
      assertEquals(
          1, usage.frequency(form.original.catalog.get(name), "Обед", LocalDate.of(2026, 11, 8)));
    }
    assertEquals(200, VaultMarkdown.day(storage.data.get(Fixtures.PATH), catalog).total, 0);
  }

  @Test
  public void nestingOldMarkedBlocksPreservesIngredientWeightsAndRecordedCalories() {
    String source =
        "### Обед\n"
            + "набор:\n"
            + "  - хлеб 20 г 40 ккал\n"
            + "  - сыр 10 г 30 ккал\n"
            + "  - хлеб 10 г 20 ккал\n"
            + "  - сыр 10 г 30 ккал\n\n"
            + "Вес:: 103\n";
    var date = LocalDate.of(2026, 11, 1);
    var marked = RecipeNesting.mark(date, source, catalog);
    assertEquals(3, VaultMarkdown.day(marked, catalog).recipes.size());
    assertTrue(marked.contains("Вес:: 103"));
    assertEquals(120, VaultMarkdown.day(marked, catalog).total, 0);
    assertEquals(marked, RecipeNesting.mark(date, marked, catalog));
    assertEquals(source, RecipeNesting.mark(date.minusDays(1), source, catalog));
  }

  @Test
  public void catalogBackfillUsesStrictSubsetsKeepsEqualSetsAcyclicAndPreservesNutrition() {
    String input =
        Fixtures.FOOD.replace(
            "<!-- food-table:end -->",
            "| дубликат бутера | хл 20 г<br>сыр 10 г | | |\n"
                + "| вода | | 0 | 100 |\n"
                + "| комплект | хл 40 г<br>сыр 20 г<br>вода 100 г | | |\n"
                + "<!-- food-table:end -->");
    String result = RecipeCatalogNesting.convert(input);
    var parsed = VaultMarkdown.catalog(result);
    assertTrue(parsed.get("комплект").ingredients.startsWith("бутерброд:"));
    assertFalse(parsed.get("бутерброд").ingredients.contains(":"));
    assertFalse(parsed.get("дубликат бутера").ingredients.contains(":"));
    assertEquals(160, parsed.get("комплект").weight, 0);
    assertEquals(87.5, parsed.get("комплект").kcal100, 1e-9);
    assertEquals(result, RecipeCatalogNesting.convert(result));
  }
}
