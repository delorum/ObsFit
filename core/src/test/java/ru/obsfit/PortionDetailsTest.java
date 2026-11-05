package ru.obsfit;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class PortionDetailsTest {
  private PortionForm recipe() {
    var form = new PortionForm(Fixtures.day(), "Обед", null);
    form.choose(form.original.catalog.get("ббр"));
    return form;
  }

  @Test
  public void selectedRecipeShowsTheSameLeafWeightsThatWillBeSaved() {
    var form = recipe();
    assertTrue(form.recipe());
    assertTrue(form.selectedRecipe());
    assertFalse(form.composition.ingredientsExpanded);
    assertEquals(
        List.of(new RecipeIngredients.Item("хлеб", 40), new RecipeIngredients.Item("сыр", 20)),
        form.composition.ingredients);
    form.changePortionWeight("30");
    assertEquals(
        List.of(new RecipeIngredients.Item("хлеб", 20), new RecipeIngredients.Item("сыр", 10)),
        form.composition.ingredients);
    assertEquals(70, form.total(), 0);
    assertTrue(form.save().noteText().contains("хлеб 20 г 40 ккал\nсыр 10 г 30 ккал"));
  }

  @Test
  public void changingOneIngredientUpdatesWeightCaloriesAndOnlyDiary() {
    var form = recipe();
    String before = form.original.foodText;
    form.composition.changeWeight(0, 20);
    form.compositionChanged();
    assertEquals("40", form.weight);
    assertEquals("250", form.kcal100);
    assertEquals(100, form.total(), 0);
    var save = form.save();
    assertNull(save.newFood());
    assertEquals(before, form.original.foodText);
    assertEquals("хл 40 г<br>сыр 20 г", form.original.catalog.get("ббр").ingredients);
    assertTrue(save.noteText().contains("хлеб 20 г 40 ккал\nсыр 20 г 60 ккал"));
  }

  @Test
  public void resizedThenEditedRecipeDoesNotRescaleTheOtherIngredient() {
    var form = recipe();
    form.changePortionWeight("30");
    form.composition.changeWeight(1, 15);
    form.compositionChanged();
    assertEquals("35", form.weight);
    assertEquals(20, form.composition.ingredients.get(0).grams(), 0);
    assertEquals(85, form.total(), 0);
    assertTrue(form.save().noteText().contains("сыр 15 г 45 ккал"));
  }

  @Test
  public void recipeDraftPreservesEditedCompositionExpansionAndQuery() {
    var form = recipe();
    form.query = "бб";
    form.composition.ingredientsExpanded = true;
    form.changePortionWeight("30");
    form.composition.changeWeight(1, 15);
    form.compositionChanged();
    var restored = PortionForm.restore(form.original, form.draft());
    assertEquals(form.composition.ingredients, restored.composition.ingredients);
    assertEquals(form.weight, restored.weight);
    assertTrue(restored.composition.ingredientsExpanded);
    assertEquals("бб", restored.query);
    assertEquals(form.save().noteText(), restored.save().noteText());
  }

  @Test
  public void intermediateInvalidWeightPreservesIngredientsAndBlocksSave() {
    var form = recipe();
    var before = List.copyOf(form.composition.ingredients);
    for (String raw : List.of("", ".", "0", "-3")) {
      form.changePortionWeight(raw);
      assertEquals(before, form.composition.ingredients);
      assertThrows(IllegalArgumentException.class, form::save);
    }
    form.changePortionWeight("30");
    assertEquals(70, form.total(), 0);
  }

  @Test
  public void switchingProductOrModeDoesNotLeakEditedComposition() {
    var form = recipe();
    form.composition.changeWeight(0, 100);
    form.compositionChanged();
    form.choose(form.original.catalog.get("сыр"));
    assertFalse(form.recipe());
    assertTrue(form.composition.ingredients.isEmpty());
    form.choose(form.original.catalog.get("ббр"));
    assertEquals(40, form.composition.ingredients.get(0).grams(), 0);
    form.startManual();
    assertTrue(form.composition.ingredients.isEmpty());
    assertFalse(form.recipe());
  }

  @Test
  public void quickAddRemainsDefaultEvenWhenVisibleRecipeWasEdited() {
    var c = new AppController();
    c.day(Fixtures.day());
    c.portion("Обед", null);
    c.chooseFood(c.state.day.catalog.get("ббр"));
    c.state.portion.changePortionWeight("30");
    assertTrue(c.portionBackToChoices());
    var quick = c.quickPortion(c.state.day.catalog.get("ббр"));
    assertEquals(140, quick.total(), 0);
    assertEquals("60", quick.weight);
  }

  @Test
  public void backFromSelectedPortionKeepsSearchAndSectionBeforeLeavingDay() {
    var c = new AppController();
    c.day(Fixtures.day());
    c.portion("Обед", null);
    c.state.portion.query = "хл";
    c.chooseFood(c.state.day.catalog.get("хл"));
    c.state.portion.changePortionWeight("10");
    assertTrue(c.portionBackToChoices());
    assertEquals(AppState.Screen.PORTION, c.state.screen);
    assertTrue(c.state.portion.choosing);
    assertEquals("хл", c.state.portion.query);
    assertEquals("Обед", c.state.portion.section);
    assertFalse(c.portionBackToChoices());
    c.portion("Заметки", c.state.day.day.entries.get(0));
    assertFalse(c.portionBackToChoices());
  }

  @Test
  public void labelsIncludeDefaultPortionAndMarkRecipeWithoutAddingRows() {
    var catalog = Fixtures.catalog().catalog;
    assertEquals("бутерброд ⊞", FoodLabels.title(catalog.get("ббр")));
    assertEquals("хлеб", FoodLabels.title(catalog.get("хл")));
    assertTrue(FoodLabels.details(catalog, catalog.get("ббр")).contains("140 ккал/порц."));
    assertTrue(FoodLabels.details(catalog, catalog.get("хл")).contains("80 ккал/порц."));
  }

  @Test
  public void highlightedMealChangesAtStartsAndStaysDuringBreaks() {
    LocalDate date = LocalDate.of(2026, 11, 5);
    assertNull(MealPlan.highlightedSection(date, date.atTime(8, 59)));
    assertEquals("Завтрак", MealPlan.highlightedSection(date, date.atTime(9, 0)));
    assertEquals("Завтрак", MealPlan.highlightedSection(date, date.atTime(10, 30)));
    assertEquals("Перекус 1", MealPlan.highlightedSection(date, date.atTime(11, 0)));
    assertEquals("Перекус 1", MealPlan.highlightedSection(date, date.atTime(12, 59)));
    assertEquals("Перекус 3", MealPlan.highlightedSection(date, date.atTime(18, 0)));
    assertEquals("Перекус 4", MealPlan.highlightedSection(date, date.atTime(23, 59)));
    assertNull(MealPlan.highlightedSection(date.minusDays(1), date.atTime(13, 0)));
  }

  @Test
  public void healthHeadersShowRecordedExerciseCountAndWeight() {
    assertEquals(new SportsDiary.Summary("—", "—"), SportsDiary.summary(Fixtures.DAY));
    var summary =
        SportsDiary.summary(
            Fixtures.DAY + "Отжимания:: 35|35\nГантелиБицепс:: 15,15|15,15\nВес:: 102,8 кг\n");
    assertEquals("2/6 упр.", summary.workout());
    assertEquals("102.8 кг", summary.weight());
    var invalid = SportsDiary.summary(Fixtures.DAY + "Пресс:: ???\nВес:: ???\n");
    assertEquals("!", invalid.workout());
    assertEquals("!", invalid.weight());
    assertEquals("—", SportsDiary.summary(Fixtures.DAY + "Пресс:: 0|0\n").workout());
  }
}
