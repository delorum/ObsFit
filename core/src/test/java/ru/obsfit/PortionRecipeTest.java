package ru.obsfit;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class PortionRecipeTest {
  private PortionForm recipe() {
    var form = new PortionForm(Fixtures.day(), "Обед", null);
    form.startNew();
    form.name = "мой бутер";
    form.aliases = "мб";
    form.composition.add(form.original.catalog.get("хл"), 40);
    form.composition.add(form.original.catalog.get("ср"), 20);
    form.recalculateRecipe();
    return form;
  }

  @Test
  public void recipeCalculatesDefaultWeightAndCaloriesBeforeNameIsEntered() {
    var form = recipe();
    form.name = "";
    assertTrue(form.recipe());
    assertEquals("60", form.defaultWeight);
    assertEquals("60", form.weight);
    assertEquals(233.33, VaultMarkdown.number(form.kcal100), 0.001);
    assertEquals(140, form.total(), 0);
    assertThrows(IllegalArgumentException.class, form::save);
  }

  @Test
  public void savesRecipeWithAliasesAndActualPortionToBothFiles() throws Exception {
    var form = recipe();
    var save = form.save();
    var storage = new Fixtures.MemoryStorage();
    var repo = new VaultRepository(storage, new Fixtures.MemoryBackups());
    repo.save(form.original, save.noteText(), save.newFood());
    var catalog = VaultMarkdown.catalog(storage.data.get("Еда.md"));
    var food = catalog.get("мб");
    assertEquals("хлеб 40 г<br>сыр 20 г", food.ingredients);
    assertEquals(60, food.weight, 0);
    assertEquals(233.333333, food.kcal100, 0.00001);
    var day = VaultMarkdown.day(storage.data.get(Fixtures.PATH), catalog);
    assertEquals("мой бутер", day.section("Обед").get(0).name);
    assertEquals(140, day.section("Обед").get(0).kcal, 0);
    assertEquals(220, day.total, 0);
    assertEquals(Fixtures.DAY, form.original.day.source);
  }

  @Test
  public void portionWeightIsIndependentOfCalculatedDefaultWeight() {
    var form = recipe();
    form.portionWeightEdited = true;
    form.weight = "30";
    var save = form.save();
    assertEquals("60", form.defaultWeight);
    assertEquals(70, form.total(), 0);
    assertTrue(save.noteText().contains("мой бутер 30 г 70 ккал"));
    assertEquals(60, save.newFood().weight, 0);
  }

  @Test
  public void editedPortionWeightSurvivesIngredientWeightChange() {
    var form = recipe();
    form.portionWeightEdited = true;
    form.weight = "15";
    form.composition.changeWeight(0, 60);
    form.recalculateRecipe();
    assertEquals("80", form.defaultWeight);
    assertEquals("15", form.weight);
    assertEquals(34, form.total(), 0);
  }

  @Test
  public void untouchedPortionFollowsNewRecipeWeightAndLastRemovalReenablesManualData() {
    var form = recipe();
    form.composition.changeWeight(0, 60);
    form.recalculateRecipe();
    assertEquals("80", form.weight);
    form.composition.remove(1);
    form.recalculateRecipe();
    assertEquals("60", form.weight);
    assertEquals("200", form.kcal100);
    form.composition.remove(0);
    form.recalculateRecipe();
    assertFalse(form.recipe());
    form.defaultWeight = "100";
    form.weight = "50";
    form.kcal100 = "120";
    assertEquals(60, form.total(), 0);
    assertEquals("", form.save().newFood().ingredients);
  }

  @Test
  public void choosingAnExistingDishPreservesItAsOneIngredient() {
    var form = new PortionForm(Fixtures.day(), "Обед", null);
    form.startNew();
    form.name = "половина бутера";
    form.composition.add(form.original.catalog.get("ббр"), 30);
    form.composition.add(form.original.catalog.get("хл"), 10);
    form.recalculateRecipe();
    assertEquals("бутерброд 30 г<br>хлеб 10 г", form.save().newFood().ingredients);
    assertEquals("40", form.defaultWeight);
    assertEquals(90, form.total(), 0);
  }

  @Test
  public void serializationRestoresCompositionExpandedStateAndCustomPortion() throws Exception {
    var form = recipe();
    form.weight = "30";
    form.portionWeightEdited = true;
    var bytes = new ByteArrayOutputStream();
    try (var out = new ObjectOutputStream(bytes)) {
      out.writeObject(form.draft());
    }
    PortionForm.Draft draft;
    try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      draft = (PortionForm.Draft) in.readObject();
    }
    var restored = PortionForm.restore(Fixtures.day(), draft);
    assertEquals(2, restored.composition.ingredients.size());
    assertTrue(restored.composition.ingredientsExpanded);
    assertTrue(restored.portionWeightEdited);
    assertEquals("30", restored.weight);
    assertEquals(70, restored.total(), 0);
    assertTrue(restored.save().noteText().contains("мой бутер 30 г 70 ккал"));
  }

  @Test
  public void changedCatalogIngredientDoesNotSilentlyLoseSavedComposition() {
    var form = recipe();
    var changed = Fixtures.day(Fixtures.DAY, Fixtures.FOOD.replace("хлеб", "батон"));
    assertThrows(IllegalStateException.class, () -> PortionForm.restore(changed, form.draft()));
  }

  @Test
  public void barcodeDoesNotReplaceCalculatedRecipeEnergy() {
    var form = recipe();
    String kcal = form.kcal100;
    form.barcode("название с упаковки", 999.0);
    assertEquals("название с упаковки", form.name);
    assertEquals(kcal, form.kcal100);
    assertEquals(140, form.total(), 0);
  }

  @Test
  public void switchingModesClearsCompositionAndDoesNotCreateAnAccidentalRecipe() {
    var form = recipe();
    form.startManual();
    assertFalse(form.recipe());
    assertTrue(form.composition.ingredients.isEmpty());
    form.startNew();
    assertFalse(form.recipe());
    form.composition.add(form.original.catalog.get("хл"), 40);
    form.recalculateRecipe();
    form.choose(form.original.catalog.get("ср"));
    assertFalse(form.recipe());
    assertTrue(form.composition.ingredients.isEmpty());
    assertEquals(60, form.total(), 0);
    assertNull(form.save().newFood());
  }

  @Test
  public void recipeNameAndAliasesAreCheckedAgainstBothDictionaries() {
    var form = recipe();
    form.name = "хлеб";
    assertThrows(IllegalArgumentException.class, form::save);
    form.name = "мой бутер";
    form.aliases = "чужой";
    assertThrows(IllegalArgumentException.class, form::save);
  }
}
