package ru.obsfit;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class SnackSectionsTest {
  private final VaultMarkdown.Catalog catalog = Fixtures.catalog().catalog;
  private static final String OLD =
      "## Заметки\n\n"
          + "## Питание\n"
          + "### Завтрак\n\n"
          + "### Обед\n\n"
          + "### Второй обед\n\n"
          + "### Ужин\n\n"
          + "### Перекусы\n"
          + "100 ккал\n\n"
          + "## Другое\n"
          + "Не еда\n";

  @Test
  public void oldSnackSectionIsShownAsFourWithoutChangingSource() {
    var day = VaultMarkdown.day(OLD, catalog);
    assertEquals(100, day.section("Перекус 4").get(0).kcal, 0);
    assertEquals("Перекус 4", day.entries.get(0).section);
    assertEquals(1, day.section("Перекусы").size());
    assertEquals(OLD, day.source);
  }

  @Test
  public void addingToFourUsesOldHeadingAndDoesNotCreateADuplicate() {
    String updated = VaultMarkdown.add(OLD, "Перекус 4", "50 ккал", catalog);
    assertTrue(updated.contains("### Перекусы\n"));
    assertFalse(updated.contains("### Перекус 4"));
    assertEquals(150, VaultMarkdown.day(updated, catalog).total, 0);
    assertEquals(2, VaultMarkdown.day(updated, catalog).section("Перекус 4").size());
  }

  @Test
  public void allFourNumberedHeadingsStaySeparateAndAreChronologicalInUi() {
    String source = "## Питание\n";
    for (int i = 1; i <= 4; i++) source += "### Перекус " + i + "\n" + (i * 10) + " ккал\n";
    var day = VaultMarkdown.day(source, catalog);
    for (int i = 1; i <= 4; i++) assertEquals(i * 10, day.section("Перекус " + i).get(0).kcal, 0);
    assertEquals(100, day.total, 0);
    assertEquals(
        List.of(
            "Заметки",
            "Завтрак",
            "Перекус 1",
            "Обед",
            "Перекус 2",
            "Второй обед",
            "Перекус 3",
            "Ужин",
            "Перекус 4"),
        VaultMarkdown.SECTIONS);
  }

  @Test
  public void missingSnacksAreCreatedBeforeTheirNextMainMeal() {
    String source = OLD;
    for (int i = 1; i <= 3; i++)
      source = VaultMarkdown.add(source, "Перекус " + i, "10 ккал", catalog);
    assertTrue(source.indexOf("### Завтрак") < source.indexOf("### Перекус 1"));
    assertTrue(source.indexOf("### Перекус 1") < source.indexOf("### Обед"));
    assertTrue(source.indexOf("### Обед") < source.indexOf("### Перекус 2"));
    assertTrue(source.indexOf("### Перекус 2") < source.indexOf("### Второй обед"));
    assertTrue(source.indexOf("### Второй обед") < source.indexOf("### Перекус 3"));
    assertTrue(source.indexOf("### Перекус 3") < source.indexOf("### Ужин"));
    assertTrue(source.contains("## Другое\nНе еда\n"));
  }

  @Test
  public void newFourIsCreatedWhenNeitherOldNorNewHeadingExists() {
    String source = OLD.replace("### Перекусы\n100 ккал\n", "");
    String updated = VaultMarkdown.add(source, "Перекус 4", "50 ккал", catalog);
    assertTrue(updated.contains("### Перекус 4\n"));
    assertFalse(updated.contains("### Перекусы"));
    assertEquals(50, VaultMarkdown.day(updated, catalog).section("Перекус 4").get(0).kcal, 0);
  }

  @Test
  public void mixedOldAndNewFourAreCountedOnceAndWritesPreferTheNewHeading() {
    String source = OLD.replace("## Другое", "### Перекус 4\n50 ккал\n## Другое");
    String updated = VaultMarkdown.add(source, "Перекус 4", "25 ккал", catalog);
    var day = VaultMarkdown.day(updated, catalog);
    assertEquals(3, day.section("Перекус 4").size());
    assertEquals(175, day.total, 0);
    assertTrue(updated.indexOf("25 ккал") > updated.indexOf("### Перекус 4"));
  }

  @Test
  public void legacyEntryCanBeEditedMovedAndRemovedWithoutLosingItsCalories() {
    var day = VaultMarkdown.day(OLD, catalog);
    String edited = VaultMarkdown.edit(OLD, day.entries.get(0), "125 ккал", catalog);
    var after = VaultMarkdown.day(edited, catalog);
    String moved = VaultMarkdown.move(edited, after.entries.get(0), "Перекус 2", catalog);
    var result = VaultMarkdown.day(moved, catalog);
    assertTrue(result.section("Перекус 4").isEmpty());
    assertEquals(125, result.section("Перекус 2").get(0).kcal, 0);
    String back =
        VaultMarkdown.move(moved, result.section("Перекус 2").get(0), "Перекус 4", catalog);
    assertFalse(back.contains("### Перекус 4"));
    var backDay = VaultMarkdown.day(back, catalog);
    assertEquals(125, backDay.total, 0);
    assertEquals(
        0,
        VaultMarkdown.day(
                VaultMarkdown.edit(back, backDay.section("Перекус 4").get(0), null, catalog),
                catalog)
            .total,
        0);
  }

  @Test
  public void legacyNameUsesLastSnackBudgetRatherThanChronologicalGuessing() {
    LocalDate date = LocalDate.of(2026, 11, 1);
    var old =
        MealPlan.guidance(
            date,
            date.atTime(11, 30),
            List.of(new MealPlan.Eaten("Завтрак", 500), new MealPlan.Eaten("Перекусы", 100)),
            2600);
    var modern =
        MealPlan.guidance(
            date,
            date.atTime(11, 30),
            List.of(new MealPlan.Eaten("Завтрак", 500), new MealPlan.Eaten("Перекус 4", 100)),
            2600);
    assertEquals(modern, old);
    assertEquals(225, old.available());
  }

  @Test
  public void snackThreeHasOwnTimeWindowAndCountsItsOwnConsumption() {
    LocalDate date = LocalDate.of(2026, 11, 1);
    var a =
        MealPlan.guidance(
            date,
            date.atTime(18, 30),
            List.of(
                new MealPlan.Eaten("Завтрак", 500), new MealPlan.Eaten("Перекус 1", 225),
                new MealPlan.Eaten("Обед", 400), new MealPlan.Eaten("Перекус 2", 225),
                new MealPlan.Eaten("Второй обед", 400), new MealPlan.Eaten("Перекус 3", 75)),
            2600);
    assertEquals("Перекус 3", a.now().name());
    assertEquals(150, a.available());
    assertEquals("Ужин", a.next().name());
  }

  @Test
  public void restoredLegacyExpansionAndDraftUseFour() {
    AppController c = new AppController();
    var day = Fixtures.day();
    c.day(day);
    c.portion("Перекусы", null);
    c.state.expanded.clear();
    c.state.expanded.add("Перекусы");
    var saved = c.state.save();
    AppController restored = new AppController();
    restored.restore(saved, List.of(day.note), day, Fixtures.catalog());
    assertEquals("Перекус 4", restored.state.portion.section);
    assertTrue(restored.state.expanded.contains("Перекус 4"));
    assertFalse(restored.state.expanded.contains("Перекусы"));
  }
}
