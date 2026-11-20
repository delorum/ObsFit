package ru.obsfit;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class NutritionScopeTest {
  private final VaultMarkdown.Catalog catalog = Fixtures.catalog().catalog;
  private static final String OUTSIDE = "## Заметки\nхл\nПлан 770 ккал\nхлеб 40 г 999 ккал\n";
  private static final String FOOD =
      "## Питание\n### Завтрак\nхл 20\n200 ккал\n#### Подраздел\nсыр 10\n### Перекус 1\n50 ккал\n";
  private static final String END =
      "## Короткая часть\n"
          + "### Спорт и диета\n"
          + "Велотренажер:: 20 мин 110 ккал\n"
          + "Пример 900 ккал\n"
          + "СъеденоКалорий:: 9999\n"
          + "Вес:: 99 ^event-example\n";

  @Test
  public void countsOnlyNutritionAndLeavesEveryOtherLineUntouched() {
    var day = VaultMarkdown.day(OUTSIDE + FOOD + END, catalog);
    assertEquals(320, day.total, 0);
    assertEquals(4, day.entries.size());
    assertTrue(day.section("Заметки").isEmpty());
    assertFalse(VaultMarkdown.SECTIONS.contains("Заметки"));
    String updated = VaultMarkdown.recalculate(OUTSIDE + FOOD + END, catalog);
    assertTrue(updated.startsWith(OUTSIDE));
    assertTrue(updated.endsWith(END.replace("9999", "320")));
    assertTrue(updated.contains("СъеденоКалорий:: 320"));
    assertEquals(updated, VaultMarkdown.recalculate(updated, catalog));
  }

  @Test
  public void absentNutritionNeverTurnsNotesOrOrphanMealHeadingsIntoFood() {
    String source = OUTSIDE + "### Завтрак\nсыр 20\n" + END;
    var day = VaultMarkdown.day(source, catalog);
    assertEquals(0, day.total, 0);
    assertTrue(day.entries.isEmpty());
    assertTrue(day.recipes.isEmpty());
    assertEquals(source.replace("9999", "0"), VaultMarkdown.recalculate(source, catalog));
  }

  @Test
  public void markdownLevelsDefineEndAndNestedSubheadingsStayInside() {
    assertEquals(
        320, VaultMarkdown.day(FOOD + "## Другое\n800 ккал\n### Завтрак\nхл\n", catalog).total, 0);
    assertEquals(
        100,
        VaultMarkdown.day("# Питание\n## Разное\n100 ккал\n# Далее\n200 ккал", catalog).total,
        0);
    assertEquals(
        80, VaultMarkdown.day("### Питание\n#### Еда\nхл\n### Конец\nсыр", catalog).total, 0);
    // Section names alone have no special meaning: actual hierarchy is authoritative.
    assertEquals(
        50,
        VaultMarkdown.day("## Питание\n### Спорт и диета\n50 ккал\n## Конец\n", catalog).total,
        0);
  }

  @Test
  public void protectedFakeNutritionHeadingsDoNotOpenOrCloseTheScope() {
    String source =
        "---\n"
            + "text: Питание\n"
            + "---\n"
            + "```\n"
            + "## Питание\n"
            + "900 ккал\n"
            + "```\n"
            + "> ## Питание\n"
            + "> 800 ккал\n"
            + "<!--\n"
            + "## Питание\n"
            + "700 ккал\n"
            + "-->\n"
            + "## Питание\n"
            + "### Завтрак\n"
            + "100 ккал\n"
            + "```\n"
            + "## Конец\n"
            + "500 ккал\n"
            + "```\n"
            + "50 ккал\n"
            + "## Конец\n"
            + "200 ккал\n";
    assertEquals(150, VaultMarkdown.day(source, catalog).total, 0);
  }

  @Test
  public void outsideFoodCannotChangeMealBudgetsGuidanceOrHistory() {
    var clean = VaultMarkdown.day(FOOD + END, catalog);
    var noisy = VaultMarkdown.day(OUTSIDE + FOOD + END + "## Заметки\nсыр 20\n", catalog);
    var date = LocalDate.of(2026, 11, 5);
    var clock = date.atTime(11, 30);
    assertEquals(MealPlan.hint(date, clock, clean), MealPlan.hint(date, clock, noisy));
    assertEquals(
        MealPlan.sectionBudgets(date, clock, clean), MealPlan.sectionBudgets(date, clock, noisy));
    assertTrue(new FoodOccurrences(catalog).find(VaultMarkdown.day(OUTSIDE, catalog)).isEmpty());
    var usage = new MealUsage();
    usage.replaceDay(date, new FoodOccurrences(catalog).find(noisy), date);
    assertEquals(0, usage.frequency(catalog.get("сыр"), "Заметки", date));
    var stale =
        MealUsage.decode(usage.encode(date).replace("meal-usage-v5", "meal-usage-v4"), date);
    assertFalse(stale.rebuilt());
    assertEquals(0, stale.frequency(catalog.get("хл"), "Завтрак", date));
  }

  @Test
  public void addManualRecipeEditDeleteAndMoveKeepAuthoritativeFieldInSync() {
    String source = OUTSIDE + "## Питание\n### Завтрак\n\n" + END;
    var snapshot = Fixtures.day(source, Fixtures.FOOD);
    var form = new PortionForm(snapshot, "Завтрак", null);
    form.startManual();
    form.name = "Кафе";
    form.manualKcal = "123";
    String added = form.save().noteText();
    assertTrue(added.contains("СъеденоКалорий:: 123"));
    assertTrue(added.startsWith(OUTSIDE));
    var entry = VaultMarkdown.day(added, catalog).entries.get(0);
    String moved = VaultMarkdown.move(added, entry, "Обед", catalog);
    assertTrue(moved.contains("СъеденоКалорий:: 123"));
    entry = VaultMarkdown.day(moved, catalog).entries.get(0);
    String changed = VaultMarkdown.edit(moved, entry, "Кафе 150 ккал", catalog);
    assertTrue(changed.contains("СъеденоКалорий:: 150"));
    String deleted =
        VaultMarkdown.edit(
            changed, VaultMarkdown.day(changed, catalog).entries.get(0), null, catalog);
    assertTrue(deleted.contains("СъеденоКалорий:: 0"));
    assertTrue(deleted.startsWith(OUTSIDE));
    assertThrows(
        IllegalArgumentException.class, () -> VaultMarkdown.add(source, "Заметки", "хл", catalog));
  }

  @Test
  public void addDoesNotTargetSameNamedHeadingOutsideNutritionAndPreservesCrLf() {
    String before = "## Заметки\n### Завтрак\nсыр 20\n";
    String source = (before + "## Питание\n### Обед\n100 ккал\n" + END).replace("\n", "\r\n");
    String added = VaultMarkdown.add(source, "Завтрак", "хл", catalog);
    assertTrue(added.startsWith(before.replace("\n", "\r\n")));
    assertTrue(added.indexOf("хлеб 40 г 80 ккал") > added.indexOf("## Питание"));
    assertEquals(180, VaultMarkdown.day(added, catalog).total, 0);
    assertFalse(added.replace("\r\n", "").contains("\n"));
  }

  @Test
  public void creatingNutritionAndMealNeverEditsUnrelatedNotesOrSports() {
    String source = OUTSIDE + END;
    String added = VaultMarkdown.add(source, "Завтрак", "хл", catalog);
    assertTrue(added.startsWith((OUTSIDE + END).replace("9999", "80")));
    assertEquals(80, VaultMarkdown.day(added, catalog).total, 0);
    assertTrue(added.contains("## Питание"));
  }

  @Test
  public void restoredDraftForRemovedNotesSectionIsRejected() {
    var snapshot = Fixtures.day(OUTSIDE + FOOD + END, Fixtures.FOOD);
    var oldForm = new PortionForm(snapshot, "Заметки", null);
    oldForm.choose(catalog.get("хл"));
    assertThrows(IllegalStateException.class, () -> PortionForm.restore(snapshot, oldForm.draft()));
    var supported = new PortionForm(snapshot, "Перекусы", null);
    assertEquals("Перекус 4", PortionForm.restore(snapshot, supported.draft()).section);
  }

  @Test
  public void draggingToMealRootCannotLandUnderAnOrphanHeadingOutsideNutrition() {
    String before = "## Заметки\n### Завтрак\nсыр 20\n";
    String source =
        before
            + "## Питание\n### Завтрак\nбутерброд:\n  - хлеб 40 г 80 ккал\n  - сыр 20 г 60 ккал\n"
            + END;
    var group = VaultMarkdown.day(source, catalog).recipes.get(0);
    String result =
        RecipeBlocks.reparent(
            source, RecipeBlocks.piece(group.entries().get(1)), null, null, catalog);
    assertTrue(result.startsWith(before));
    assertEquals(140, VaultMarkdown.day(result, catalog).total, 0);
    var day = VaultMarkdown.day(result, catalog);
    assertEquals(1, day.recipes.size());
    assertEquals(1, day.recipes.get(0).entries().size());
    assertTrue(day.entries.get(0).line < day.recipes.get(0).line());
  }
}
