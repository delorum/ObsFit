package ru.obsfit;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class MealCatalogTest {
  private static final String FOOD =
      Fixtures.FOOD.replace(
          "<!-- food-table:end -->",
          "| вода | | 0 | 100 |\n"
              + "| набор | бутерброд 60 г<br>вода 100 г | | |\n"
              + "<!-- food-table:end -->");
  private static final String MEAL =
      "## Питание\n"
          + "### Завтрак\n"
          + "бутерброд:\n"
          + "  - хлеб 20 г 40 ккал\n"
          + "  - сыр 35 г 105 ккал\n"
          + "сыр 10 г 30 ккал\n\n"
          + "### Ужин\n"
          + "хлеб 100 г 200 ккал\n\n"
          + "### Спорт и диета\n"
          + "Вес:: 101\n"
          + "СъеденоКалорий:: 375\n";

  private VaultRepository.CatalogSnapshot catalog() {
    return new VaultRepository.CatalogSnapshot(FOOD, Fixtures.REPLACEMENTS);
  }

  private CatalogForm form(String source) {
    return MealCatalog.create(Fixtures.day(source, FOOD), catalog(), "Завтрак");
  }

  private CatalogForm form() {
    return form(MEAL);
  }

  @Test
  public void countsVisibleRootsInsteadOfAllNestedIngredients() {
    var day = Fixtures.day(MEAL, FOOD).day;
    assertEquals(2, MealCatalog.count(day, "Завтрак"));
    assertEquals(1, MealCatalog.count(day, "Ужин"));
    assertEquals(0, MealCatalog.count(day, "Обед"));
    String onlyGroup = MEAL.replace("\nсыр 10 г 30 ккал", "");
    assertEquals(1, MealCatalog.count(Fixtures.day(onlyGroup, FOOD).day, "Завтрак"));
    assertThrows(IllegalStateException.class, () -> form(onlyGroup));
    assertThrows(IllegalStateException.class, () -> form("### Завтрак\n"));
  }

  @Test
  public void transfersActualTreeWeightsAndMealOnlyWithoutChangingSources() {
    var form = form();
    assertNull(form.food);
    assertEquals("", form.name);
    assertTrue(form.ingredientsExpanded);
    assertTrue(form.dirty);
    assertEquals(2, form.ingredients.size());
    var sandwich = form.ingredients.get(0);
    assertEquals("бутерброд", sandwich.name());
    assertEquals(55, sandwich.grams(), 0);
    assertEquals(
        List.of(20d, 35d),
        sandwich.children().stream().map(RecipeIngredients.Item::grams).toList());
    assertEquals("сыр", form.ingredients.get(1).name());
    assertEquals(10, form.ingredients.get(1).grams(), 0);
    form.name = "Мой завтрак";
    assertEquals(175, form.preview().portionCalories(), 0);
    assertEquals("65", form.weight);
    assertEquals(FOOD, form.original.foodText);
    var saved = VaultMarkdown.catalog(form.save());
    assertEquals(40, saved.get("хлеб").weight, 0);
    assertEquals(60, saved.get("бутерброд").weight, 0);
    assertEquals(65, saved.get("Мой завтрак").weight, 0);
  }

  @Test
  public void editsLeafWeightsRemovesAndAddsBeforeSaving() {
    var form = form();
    form.name = "Другой завтрак";
    var bread =
        form.nodes().stream().filter(n -> n.item().name().equals("хлеб")).findFirst().orElseThrow();
    form.changeWeight(bread.index(), 15);
    form.remove(1);
    form.add(form.original.catalog.get("вода"), 200);
    var saved = VaultMarkdown.catalog(form.save()).get(form.name);
    assertEquals(250, saved.weight, 0);
    assertEquals(54, saved.kcal100, 0); // 30 kcal bread + 105 cheese; water 0
    assertTrue(saved.ingredients.contains("бутерброд:"));
    assertTrue(saved.ingredients.contains("хлеб 15 г"));
    assertTrue(saved.ingredients.contains("вода 200 г"));
  }

  @Test
  public void keepsDistinctPortionsAndDuplicateIngredientsAcrossGroups() {
    var form =
        form(
            MEAL.replace(
                "\nсыр 10 г 30 ккал", "\nбутерброд:\n  - хлеб 10 г 20 ккал\n  - сыр 15 г 45 ккал"));
    assertEquals(2, form.ingredients.size());
    assertEquals("бутерброд", form.ingredients.get(0).name());
    assertEquals("бутерброд", form.ingredients.get(1).name());
    assertEquals(55, form.ingredients.get(0).grams(), 0);
    assertEquals(25, form.ingredients.get(1).grams(), 0);
  }

  @Test
  public void preservesNestedRecipesSeveralLevelsAndTheirOverrides() {
    String source =
        "### Завтрак\n"
            + "набор:\n"
            + "  - бутерброд:\n"
            + "    - хлеб 12 г 24 ккал\n"
            + "    - сыр 8 г 24 ккал\n"
            + "  - вода 250 г 0 ккал\n"
            + "сыр 5 г 15 ккал\n";
    var form = form(source);
    var nested = form.ingredients.get(0);
    assertEquals("набор", nested.name());
    assertEquals(270, nested.grams(), 0);
    assertEquals(20, nested.children().get(0).grams(), 0);
    assertEquals(12, nested.children().get(0).children().get(0).grams(), 0);
    form.name = "Глубокий рецепт";
    assertNotNull(VaultMarkdown.catalog(form.save()).get(form.name));
  }

  @Test
  public void diaryOnlyGroupNamePromotesContentsWithoutInventingCatalogProducts() {
    var form = form(MEAL.replace("бутерброд:", "Переименованная порция:"));
    assertEquals(3, form.ingredients.size());
    assertEquals(
        List.of("хлеб", "сыр", "сыр"),
        form.ingredients.stream().map(RecipeIngredients.Item::name).toList());
    assertEquals(1, form.importNotes.size());
    assertTrue(form.unresolved.isEmpty());
    form.name = "Новый рецепт";
    assertNull(VaultMarkdown.catalog(form.save()).get("Переименованная порция"));
  }

  @Test
  public void manualAndUnknownRowsRemainVisibleAndBlockSilentPartialSave() {
    var form =
        form(MEAL.replace("\nсыр 10 г 30 ккал", "\nсыр 70 ккал\nнеизвестная еда 40 г 99 ккал"));
    assertEquals(2, form.unresolved.size());
    form.name = "Рецепт без пропусков";
    assertNotNull(form.preview().error());
    assertThrows(IllegalArgumentException.class, form::save);
    assertTrue(form.unresolved.stream().anyMatch(s -> s.contains("сыр 70 ккал")));
    form.unresolved.clear(); // explicit UI exclusions, does not remove diary rows
    assertNotNull(VaultMarkdown.catalog(form.save()).get(form.name));
  }

  @Test
  public void allUnsupportedRowsCanBeDraftedAndExplicitlyExcluded() {
    var form = form("### Завтрак\n100 ккал\nдругое 70 ккал\n");
    assertEquals(2, form.unresolved.size());
    assertTrue(form.ingredients.isEmpty());
    var restored = CatalogForm.restore(catalog(), form.draft());
    assertEquals(form.unresolved, restored.unresolved);
    assertThrows(
        IllegalArgumentException.class,
        () -> MealCatalog.create(Fixtures.day(MEAL, FOOD), catalog(), "Заметки"));
  }

  @Test
  public void zeroCalorieProductsAndLegacyWeightedRecipesTransfer() {
    var form = form("### Завтрак\nбутерброд 120 г 280 ккал\nвода 200 г 0 ккал\n");
    assertEquals(120, form.ingredients.get(0).grams(), 0);
    assertEquals(2, form.ingredients.get(0).children().size());
    assertEquals(200, form.ingredients.get(1).grams(), 0);
    form.name = "Вода и бутерброд";
    assertEquals(280, form.preview().portionCalories(), 0);
  }

  @Test
  public void cancelSaveAndRestoreReturnToDayRatherThanCatalog() {
    var c = new AppController();
    c.day(Fixtures.day(MEAL, FOOD));
    c.mealToCatalog(catalog(), "Завтрак");
    assertEquals(AppState.Screen.CATALOG_EDITOR, c.state.screen);
    assertTrue(c.state.returnMeal);
    c.state.catalogForm.name = "Мой рецепт";
    var saved = c.state.save();
    var restored = new AppController();
    restored.restore(saved, List.of(c.state.day.note), c.state.day, catalog());
    assertTrue(restored.state.returnMeal);
    assertEquals("Мой рецепт", restored.state.catalogForm.name);
    restored.returnFromCatalog(c.state.day);
    assertEquals(AppState.Screen.DAY, restored.state.screen);
    assertFalse(restored.state.returnMeal);
    assertNull(restored.state.catalogForm);
    assertEquals(MEAL, restored.state.day.day.source);
  }

  @Test
  public void staleCatalogDraftReturnsToDayWithError() {
    var c = new AppController();
    c.day(Fixtures.day(MEAL, FOOD));
    c.mealToCatalog(catalog(), "Завтрак");
    var changed = new VaultRepository.CatalogSnapshot(FOOD + "\nChanged", Fixtures.REPLACEMENTS);
    var restored = new AppController();
    restored.restore(c.state.save(), List.of(c.state.day.note), c.state.day, changed);
    assertEquals(AppState.Screen.DAY, restored.state.screen);
    assertNotNull(restored.state.error);
    assertFalse(restored.state.returnMeal);
  }

  @Test
  public void duplicateNamesAreRejectedAndDictionarySaveDoesNotTouchDiary() throws Exception {
    var storage = new Fixtures.MemoryStorage();
    storage.data.put("Еда.md", FOOD);
    storage.data.put(Fixtures.PATH, MEAL);
    var repo = new VaultRepository(storage, new Fixtures.MemoryBackups());
    var day = repo.load(Fixtures.day().note);
    var form = MealCatalog.create(day, repo.loadCatalog(), "Завтрак");
    form.name = "бутерброд";
    assertThrows(IllegalArgumentException.class, form::save);
    form.name = "Из завтрака";
    repo.saveCatalog(form.original, form.save());
    assertEquals(MEAL, storage.data.get(Fixtures.PATH));
    assertNotNull(VaultMarkdown.catalog(storage.data.get("Еда.md")).get(form.name));
    assertEquals(Fixtures.REPLACEMENTS, storage.data.get("Замены.md"));
  }

  @Test
  public void unresolvedRowsAndImportNotesSurviveDraftRestore() {
    var form =
        form(
            MEAL.replace("бутерброд:", "Своя группа:").replace("\nсыр 10 г 30 ккал", "\n150 ккал"));
    var restored = CatalogForm.restore(catalog(), form.draft());
    assertEquals(form.unresolved, restored.unresolved);
    assertEquals(form.importNotes, restored.importNotes);
    assertEquals(form.ingredients, restored.ingredients);
  }

  @Test
  public void calculatorStillEditsImportedIngredientsWithoutLosingDayReturn() {
    var c = new AppController();
    c.day(Fixtures.day(MEAL, FOOD));
    c.mealToCatalog(catalog(), "Завтрак");
    var form = c.state.catalogForm;
    var bread =
        form.nodes().stream().filter(n -> n.item().name().equals("хлеб")).findFirst().orElseThrow();
    c.calculator(CalculatorForm.Target.CATALOG_INGREDIENT, bread.index(), null, "10+5", 0);
    assertEquals(AppState.Screen.CALCULATOR, c.state.screen);
    assertTrue(c.state.returnMeal);
    var restored = new AppController();
    restored.restore(c.state.save(), List.of(c.state.day.note), c.state.day, catalog());
    assertEquals(AppState.Screen.CALCULATOR, restored.state.screen);
    assertTrue(restored.state.returnMeal);
    assertEquals(2, restored.state.catalogForm.ingredients.size());
    assertEquals(MEAL, restored.state.day.day.source);
  }

  @Test
  public void legacySnackSectionUsesSameCountingAndImport() {
    String source = "### Перекусы\nхлеб 30 г 60 ккал\nсыр 10 г 30 ккал\n";
    var day = Fixtures.day(source, FOOD);
    assertEquals(2, MealCatalog.count(day.day, "Перекус 4"));
    var form = MealCatalog.create(day, catalog(), "Перекус 4");
    assertEquals(
        List.of(30d, 10d), form.ingredients.stream().map(RecipeIngredients.Item::grams).toList());
  }

  @Test
  public void newCatalogDefaultsNeverReplacePortionWeightsAndConcurrentCatalogWriteIsRejected()
      throws Exception {
    String changedFood = FOOD.replace("| 200 | 40 |", "| 200 | 500 |");
    var current = new VaultRepository.CatalogSnapshot(changedFood, Fixtures.REPLACEMENTS);
    var form = MealCatalog.create(Fixtures.day(MEAL, FOOD), current, "Завтрак");
    assertEquals(500, current.catalog.get("хлеб").weight, 0);
    assertEquals(20, form.ingredients.get(0).children().get(0).grams(), 0);
    var storage = new Fixtures.MemoryStorage();
    storage.data.put("Еда.md", changedFood + "\nВнешнее изменение");
    storage.data.put(Fixtures.PATH, MEAL + "Внешняя запись\n");
    var repo = new VaultRepository(storage, new Fixtures.MemoryBackups());
    form.name = "Завтрак после синхронизации";
    assertThrows(
        VaultRepository.StaleSnapshot.class, () -> repo.saveCatalog(form.original, form.save()));
    assertEquals(MEAL + "Внешняя запись\n", storage.data.get(Fixtures.PATH));
    assertEquals(changedFood + "\nВнешнее изменение", storage.data.get("Еда.md"));
  }
}
