package ru.obsfit;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class DiaryRecipeTest {
  private static final String SOURCE =
      "## Заметки\n"
          + "Текст\n\n"
          + "## Питание\n"
          + "### Завтрак\n"
          + "Завтрак дома:\n"
          + "  - бутерброд:\n"
          + "    - хлеб 40 г 80 ккал\n"
          + "    - сыр 20 г 60 ккал\n"
          + "  - кофе 5 ккал\n"
          + "хлеб 10 г 20 ккал\n\n"
          + "### Ужин\n"
          + "сыр 10 г 30 ккал\n\n"
          + "### Спорт и диета\n"
          + "Вес:: 101\n"
          + "СъеденоКалорий:: 195\n";
  private final VaultMarkdown.Catalog catalog = VaultMarkdown.catalog(Fixtures.FOOD);

  private RecipeBlocks.Group group(String source, String name) {
    return VaultMarkdown.day(source, catalog).recipes.stream()
        .filter(g -> g.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private VaultMarkdown.Entry entry(String source, String name, String section) {
    return VaultMarkdown.day(source, catalog).entries.stream()
        .filter(e -> e.name.equals(name) && e.section.equals(section))
        .findFirst()
        .orElseThrow();
  }

  private DiaryRecipeForm form(String name) {
    return new DiaryRecipeForm(Fixtures.day(SOURCE, Fixtures.FOOD), group(SOURCE, name));
  }

  private int draftLine(DiaryRecipeForm f, String name) {
    return f.day().entries.stream().filter(e -> e.name.equals(name)).findFirst().orElseThrow().line;
  }

  private String move(
      String source,
      RecipeBlocks.Piece piece,
      RecipeBlocks.Group target,
      RecipeBlocks.Piece after) {
    String result = RecipeBlocks.reparent(source, piece, target, after, catalog);
    assertEquals(
        VaultMarkdown.day(source, catalog).total, VaultMarkdown.day(result, catalog).total, 0);
    assertTrue(result.contains("Вес:: 101"));
    assertEquals(
        VaultMarkdown.day(source, catalog).entries.size(),
        VaultMarkdown.day(result, catalog).entries.size());
    return result;
  }

  @Test
  public void renameOnlyThisDiaryInstanceAndPreserveManualCaloriesAndCatalog() {
    var f = form("Завтрак дома");
    f.name = "Гранола и капучино";
    String changed = f.save();
    assertTrue(changed.contains("Гранола и капучино:\n  - бутерброд:"));
    assertEquals(195, VaultMarkdown.day(changed, catalog).total, 0);
    assertEquals(5, entry(changed, "кофе", "Завтрак").kcal, 0);
    assertEquals(Fixtures.FOOD, f.original.foodText);
    assertEquals(SOURCE, f.original.day.source);
  }

  @Test
  public void renameNestedGroupPreservesItsParentAndSibling() {
    var f = form("бутерброд");
    f.name = "Мой бутерброд";
    var changed = f.save();
    assertTrue(changed.contains("  Мой бутерброд:\n    - хлеб"));
    assertEquals(
        group(changed, "Завтрак дома").line(), group(changed, "Мой бутерброд").parentLine());
    assertTrue(changed.contains("  - кофе 5 ккал"));
  }

  @Test
  public void removeAddAndWeightAffectOnlyDraftUntilSave() {
    var f = form("Завтрак дома");
    f.remove(draftLine(f, "сыр"));
    f.add(f.original.catalog.get("сыр"), "30,5");
    f.weight(draftLine(f, "хлеб"), "20");
    assertEquals(
        5,
        f.day().entries.stream().filter(e -> e.name.equals("кофе")).findFirst().orElseThrow().kcal,
        0);
    assertEquals(SOURCE, f.original.day.source);
    String changed = f.save();
    assertEquals(
        187,
        VaultMarkdown.day(changed, catalog).total,
        0); // bread 40 + cheese 92 + coffee 5 + outer bread 20 + dinner 30
    assertTrue(changed.contains("хлеб 20 г 40 ккал"));
    assertTrue(changed.contains("сыр 30.5 г 92 ккал"));
  }

  @Test
  public void canReplaceAllIngredientsAndAddNestedRecipe() {
    var f = form("бутерброд");
    f.remove(draftLine(f, "сыр"));
    f.remove(draftLine(f, "хлеб"));
    assertTrue(f.day().entries.isEmpty());
    assertThrows(IllegalArgumentException.class, f::save);
    f.add(f.original.catalog.get("бутерброд"), "120");
    assertEquals(280, f.day().total, 0);
    assertEquals(2, f.day().recipes.size());
    assertTrue(f.save().contains("хлеб 80 г 160 ккал"));
  }

  @Test
  public void removingNestedGroupPreservesOtherManualEntry() {
    var f = form("Завтрак дома");
    f.remove(f.day().recipes.stream().filter(g -> g.line() != 2).findFirst().orElseThrow().line());
    assertEquals(5, f.day().total, 0);
    assertFalse(f.save().contains("бутерброд:"));
  }

  @Test
  public void rejectsInvalidNameWeightAndStaleDraft() {
    var f = form("Завтрак дома");
    for (var name : List.of("", "# Заголовок", "Название:\n### Ужин", "x:: y")) {
      f.name = name;
      assertThrows(IllegalArgumentException.class, f::save);
    }
    for (var weight : List.of("0", "-1", "NaN"))
      assertThrows(IllegalArgumentException.class, () -> f.weight(draftLine(f, "хлеб"), weight));
    assertThrows(
        IllegalStateException.class,
        () ->
            DiaryRecipeForm.restore(
                Fixtures.day(SOURCE + "Другой текст", Fixtures.FOOD), f.draft()));
  }

  @Test
  public void draftSurvivesControllerRestoreAndCancelDiscardsChanges() {
    var c = new AppController();
    c.day(Fixtures.day(SOURCE, Fixtures.FOOD));
    c.editRecipe(group(SOURCE, "Завтрак дома"));
    c.state.diaryRecipe.name = "Другое";
    c.state.diaryRecipe.dirty = true;
    var saved = c.state.save();
    var restored = new AppController();
    restored.restore(saved, List.of(c.state.day.note), c.state.day, Fixtures.catalog());
    assertEquals(AppState.Screen.DIARY_RECIPE, restored.state.screen);
    assertEquals("Другое", restored.state.diaryRecipe.name);
    restored.day(restored.state.day);
    assertNull(restored.state.diaryRecipe);
    assertEquals(SOURCE, restored.state.day.day.source);
    restored.restore(
        saved,
        List.of(c.state.day.note),
        Fixtures.day(SOURCE + "change", Fixtures.FOOD),
        Fixtures.catalog());
    assertEquals(AppState.Screen.DAY, restored.state.screen);
    assertNotNull(restored.state.error);
  }

  @Test
  public void calculatorReturnsToDiaryAndChangesOnlyItsDraft() {
    var c = new AppController();
    c.day(Fixtures.day(SOURCE, Fixtures.FOOD));
    c.editRecipe(group(SOURCE, "Завтрак дома"));
    int line = draftLine(c.state.diaryRecipe, "хлеб");
    c.calculator(CalculatorForm.Target.DIARY_WEIGHT, line, "хлеб", "", 0);
    c.state.calculator.expression = "20+10";
    c.applyCalculator();
    assertEquals(AppState.Screen.DIARY_RECIPE, c.state.screen);
    assertEquals(
        30,
        c.state.diaryRecipe.day().entries.stream()
            .filter(e -> e.line == line)
            .findFirst()
            .orElseThrow()
            .weight,
        0);
    assertEquals(SOURCE, c.state.day.day.source);
  }

  @Test
  public void dropIntoGroupAndDropBackToMealPreservesTotals() {
    var outside =
        VaultMarkdown.day(SOURCE, catalog).entries.stream()
            .filter(e -> e.raw.equals("хлеб 10 г 20 ккал"))
            .findFirst()
            .orElseThrow();
    var result = move(SOURCE, RecipeBlocks.piece(outside), group(SOURCE, "бутерброд"), null);
    assertEquals(3, group(result, "бутерброд").entries().size());
    var inside = entry(result, "хлеб", "Завтрак");
    result = move(result, RecipeBlocks.piece(inside), null, null);
    assertEquals("хлеб 10 г 20 ккал", VaultMarkdown.day(result, catalog).entries.get(0).raw);
    assertEquals(2, group(result, "бутерброд").entries().size());
  }

  @Test
  public void explicitMealLevelInsertionAfterNestedItemDoesNotSplitItsRootGroup() {
    var bread = entry(SOURCE, "хлеб", "Завтрак");
    var coffee = entry(SOURCE, "кофе", "Завтрак");
    String result = move(SOURCE, RecipeBlocks.piece(bread), null, RecipeBlocks.piece(coffee));
    var moved =
        VaultMarkdown.day(result, catalog).entries.stream()
            .filter(e -> e.raw.equals("- хлеб 40 г 80 ккал"))
            .findFirst()
            .orElseThrow();
    assertTrue(moved.line >= group(result, "Завтрак дома").end());
    assertEquals(1, group(result, "бутерброд").entries().size());
  }

  @Test
  public void movesNestedDishAsSubtreeAndCleansEmptyAncestors() {
    String result = move(SOURCE, RecipeBlocks.piece(group(SOURCE, "бутерброд")), null, null);
    assertEquals(-1, group(result, "бутерброд").parentLine());
    assertEquals(1, group(result, "Завтрак дома").entries().size());
    var coffee = entry(result, "кофе", "Завтрак");
    result = move(result, RecipeBlocks.piece(coffee), group(result, "бутерброд"), null);
    assertFalse(result.contains("Завтрак дома:"));
    assertEquals(3, group(result, "бутерброд").entries().size());
  }

  @Test
  public void refusesCyclesOtherMealsAndStaleDragPayload() {
    var root = group(SOURCE, "Завтрак дома");
    var nested = group(SOURCE, "бутерброд");
    assertThrows(
        IllegalArgumentException.class,
        () -> RecipeBlocks.reparent(SOURCE, RecipeBlocks.piece(root), nested, null, catalog));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecipeBlocks.reparent(
                SOURCE, RecipeBlocks.piece(entry(SOURCE, "сыр", "Ужин")), nested, null, catalog));
    String changed = SOURCE.replace("хлеб 40 г 80 ккал", "хлеб 45 г 90 ккал");
    assertThrows(
        IllegalStateException.class,
        () -> RecipeBlocks.reparent(changed, RecipeBlocks.piece(root), null, null, catalog));
    assertEquals(
        SOURCE,
        RecipeBlocks.reparent(
            SOURCE, RecipeBlocks.piece(nested), null, RecipeBlocks.piece(nested), catalog));
  }

  @Test
  public void dragAndEditorPreserveCrLfAndOtherSections() {
    String source = SOURCE.replace("\n", "\r\n");
    var f = new DiaryRecipeForm(Fixtures.day(source, Fixtures.FOOD), group(source, "бутерброд"));
    f.name = "Новый";
    String saved = f.save();
    assertFalse(saved.replace("\r\n", "").contains("\n"));
    var result =
        RecipeBlocks.reparent(
            source, RecipeBlocks.piece(group(source, "бутерброд")), null, null, catalog);
    assertFalse(result.replace("\r\n", "").contains("\n"));
    assertTrue(result.contains("### Ужин\r\nсыр 10 г 30 ккал"));
  }

  @Test
  public void diarySaveCreatesBackupWithoutWritingCatalogAndRejectsExternalChanges()
      throws Exception {
    var storage = new Fixtures.MemoryStorage();
    storage.data.put(Fixtures.PATH, SOURCE);
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(storage, backups);
    var original = repo.load(Fixtures.day().note);
    var recipe = original.day.recipes.get(0);
    var f = new DiaryRecipeForm(original, recipe);
    f.name = "Мой новый завтрак";
    repo.save(original, f.save(), null);
    assertEquals(Fixtures.FOOD, storage.data.get("Еда.md"));
    assertEquals(Fixtures.REPLACEMENTS, storage.data.get("Замены.md"));
    assertFalse(backups.saved.isEmpty());
    assertTrue(storage.data.get(Fixtures.PATH).contains("Мой новый завтрак:"));
    var refreshed = repo.load(original.note);
    var draft = new DiaryRecipeForm(refreshed, refreshed.day.recipes.get(0));
    draft.name = "Другая правка";
    String external = refreshed.day.source + "Внешние изменения\n";
    storage.data.put(Fixtures.PATH, external);
    assertThrows(
        VaultRepository.StaleSnapshot.class, () -> repo.save(refreshed, draft.save(), null));
    assertEquals(external, storage.data.get(Fixtures.PATH));
  }

  @Test
  public void addingZeroCaloriesAndCalculatorNewIngredientKeepsOriginalRecipeUntouched() {
    String food =
        Fixtures.FOOD.replace(
            "<!-- food-table:end -->", "| чай | | 0 | 200 |\n<!-- food-table:end -->");
    var c = new AppController();
    c.day(Fixtures.day(SOURCE, food));
    c.editRecipe(c.state.day.day.recipes.get(0));
    c.calculator(CalculatorForm.Target.DIARY_NEW_WEIGHT, -1, "чай", "75+25", 0);
    assertEquals("75+25", c.state.calculator.expression);
    c.state.calculator.expression = "100+150";
    c.applyCalculator();
    assertEquals(AppState.Screen.DIARY_RECIPE, c.state.screen);
    var tea =
        c.state.diaryRecipe.day().entries.stream()
            .filter(e -> e.name.equals("чай"))
            .findFirst()
            .orElseThrow();
    assertEquals(250, tea.weight, 0);
    assertEquals(0, tea.kcal, 0);
    assertFalse(c.state.day.day.source.contains("чай"));
    assertEquals(food, c.state.day.foodText);
  }

  @Test
  public void droppingIntoCollapsedNestedRecipeOpensItsParentsAtUpdatedLinePositions() {
    var target = group(SOURCE, "бутерброд");
    var outside =
        VaultMarkdown.day(SOURCE, catalog).entries.stream()
            .filter(e -> e.raw.equals("хлеб 10 г 20 ккал"))
            .findFirst()
            .orElseThrow();
    String changed =
        RecipeBlocks.reparent(SOURCE, RecipeBlocks.piece(outside), target, null, catalog);
    var c = new AppController();
    c.day(Fixtures.day(changed, Fixtures.FOOD));
    c.expandRecipe(target);
    assertTrue(c.state.expanded.contains("Завтрак"));
    for (var group : c.state.day.day.recipes)
      assertTrue(c.state.expanded.contains("recipe:" + c.state.day.note.path + ":" + group.line()));
  }

  @Test
  public void dropAfterNestedIngredientUsesItsImmediateParentAndExactOrder() {
    var after = entry(SOURCE, "сыр", "Завтрак");
    var outside =
        VaultMarkdown.day(SOURCE, catalog).entries.stream()
            .filter(e -> e.raw.equals("хлеб 10 г 20 ккал"))
            .findFirst()
            .orElseThrow();
    var parent = RecipeBlocks.parent(VaultMarkdown.day(SOURCE, catalog), after.line);
    assertEquals("бутерброд", parent.name());
    String result = move(SOURCE, RecipeBlocks.piece(outside), parent, RecipeBlocks.piece(after));
    var group = group(result, "бутерброд");
    assertEquals(3, group.entries().size());
    assertEquals(
        List.of("хлеб", "сыр", "хлеб"), group.entries().stream().map(e -> e.name).toList());
    assertEquals(10, group.entries().get(2).weight, 0);
    assertEquals("кофе", entry(result, "кофе", "Завтрак").name);
  }

  @Test
  public void reordersSiblingsWithinRecipeInsteadOfMovingOut() {
    var day = VaultMarkdown.day(SOURCE, catalog);
    var bread = entry(SOURCE, "хлеб", "Завтрак");
    var cheese = entry(SOURCE, "сыр", "Завтрак");
    String result =
        move(
            SOURCE,
            RecipeBlocks.piece(bread),
            RecipeBlocks.parent(day, cheese.line),
            RecipeBlocks.piece(cheese));
    assertEquals(
        List.of("сыр", "хлеб"),
        group(result, "бутерброд").entries().stream().map(e -> e.name).toList());
    assertEquals(2, group(result, "Завтрак дома").entries().size() - 1);
  }

  @Test
  public void dropFromParentIntoNestedRecipeAfterItsFirstIngredient() {
    var day = VaultMarkdown.day(SOURCE, catalog);
    var after = entry(SOURCE, "хлеб", "Завтрак");
    var coffee = entry(SOURCE, "кофе", "Завтрак");
    String result =
        move(
            SOURCE,
            RecipeBlocks.piece(coffee),
            RecipeBlocks.parent(day, after.line),
            RecipeBlocks.piece(after));
    assertEquals(
        List.of("хлеб", "кофе", "сыр"),
        group(result, "бутерброд").entries().stream().map(e -> e.name).toList());
    assertEquals(5, entry(result, "кофе", "Завтрак").kcal, 0);
  }

  @Test
  public void rootIngredientHasNoParentAndDropRetainsMealLevel() {
    var outside =
        VaultMarkdown.day(SOURCE, catalog).entries.stream()
            .filter(e -> e.raw.equals("хлеб 10 г 20 ккал"))
            .findFirst()
            .orElseThrow();
    assertNull(RecipeBlocks.parent(VaultMarkdown.day(SOURCE, catalog), outside.line));
    String result =
        move(
            SOURCE,
            RecipeBlocks.piece(entry(SOURCE, "кофе", "Завтрак")),
            null,
            RecipeBlocks.piece(outside));
    var coffee = entry(result, "кофе", "Завтрак");
    assertNull(RecipeBlocks.parent(VaultMarkdown.day(result, catalog), coffee.line));
    assertTrue(result.contains("хлеб 10 г 20 ккал\n- кофе 5 ккал"));
  }

  @Test
  public void rejectsAnchorInDifferentParentAndMovesWholeDishAfterIngredient() {
    var root = group(SOURCE, "Завтрак дома");
    var nested = group(SOURCE, "бутерброд");
    var cheese = entry(SOURCE, "сыр", "Завтрак");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecipeBlocks.reparent(
                SOURCE,
                RecipeBlocks.piece(entry(SOURCE, "кофе", "Завтрак")),
                root,
                RecipeBlocks.piece(cheese),
                catalog));
    var coffee = entry(SOURCE, "кофе", "Завтрак");
    String result = move(SOURCE, RecipeBlocks.piece(nested), root, RecipeBlocks.piece(coffee));
    assertEquals(root.line(), group(result, "бутерброд").parentLine());
    assertTrue(entry(result, "кофе", "Завтрак").line < group(result, "бутерброд").line());
  }

  @Test
  public void holdStartsOnlyAfterScheduledTimeoutAndCancelsOnImmediateMotionOrRelease() {
    var h = new HoldTracker(8);
    h.down(0, 0);
    assertFalse(h.held());
    h.cancel();
    assertFalse(h.fire());
    h.down(0, 0);
    h.move(20, 0);
    assertFalse(h.fire());
    h.down(0, 0);
    h.move(0, 20);
    assertFalse(h.fire());
    h.down(0, 0);
    h.move(3, 3);
    assertTrue(h.fire());
    assertTrue(h.held());
    assertFalse(h.fire());
    h.down(0, 0);
    assertFalse(h.held());
    assertTrue(h.fire());
  }
}
