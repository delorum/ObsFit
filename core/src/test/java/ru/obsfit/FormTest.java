package ru.obsfit;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public final class FormTest {
  @Test
  public void newProductCreatesCatalogEntryAndPortionTogether() {
    var form = new PortionForm(Fixtures.day(), "Обед", null);
    form.startNew();
    form.name = "каша";
    form.aliases = "кш\nкаша овсяная";
    form.defaultWeight = "200";
    form.weight = "150";
    form.kcal100 = "120";
    var save = form.save();
    assertEquals(200, save.newFood().weight, 0);
    assertEquals(List.of("кш", "каша овсяная"), save.newFood().aliases);
    assertTrue(save.noteText().contains("каша 150 г 180 ккал"));
    assertTrue(save.noteText().contains("СъеденоКалорий:: 260"));
  }

  @Test
  public void knownPortionDoesNotChangeCatalog() {
    var form = new PortionForm(Fixtures.day(), "Ужин", null);
    form.choose(form.original.catalog.get("ср"));
    form.weight = "35";
    var save = form.save();
    assertNull(save.newFood());
    assertEquals(300, form.original.catalog.get("ср").kcal100, 0);
    assertTrue(save.noteText().contains("сыр 35 г 105 ккал"));
  }

  @Test
  public void manualCaloriesRemainIndependentOfWeight() {
    var original = Fixtures.day(Fixtures.DAY + "другая еда 123.5 ккал\n", Fixtures.FOOD);
    var entry = original.day.entries.get(1);
    var form = new PortionForm(original, "Заметки", entry);
    assertTrue(form.manual);
    form.manualKcal = "150,5";
    assertEquals(150.5, form.total(), 0);
    assertTrue(form.save().noteText().contains("другая еда 150.5 ккал"));
  }

  @Test
  public void duplicatesAreRejectedBeforeAnyIo() {
    var form = new PortionForm(Fixtures.day(), "Обед", null);
    form.startNew();
    form.name = "хлеб";
    form.kcal100 = "200";
    assertThrows(IllegalArgumentException.class, form::save);
    form.name = "чужой";
    assertThrows(IllegalArgumentException.class, form::save);
  }

  @Test
  public void formDraftPreservesInvalidIntermediateInput() {
    var form = new PortionForm(Fixtures.day(), "Ужин", null);
    form.startNew();
    form.name = "черновик";
    form.weight = "";
    form.kcal100 = "12,";
    form.dirty = true;
    var restored = PortionForm.restore(Fixtures.day(), form.draft());
    assertEquals("", restored.weight);
    assertEquals("12,", restored.kcal100);
    assertTrue(restored.dirty);
    assertThrows(IllegalArgumentException.class, restored::save);
  }

  @Test
  public void previewNeverMutatesSourceAndIngredientsAreCalculated() {
    var form = new CatalogForm(Fixtures.catalog(), null);
    form.name = "мой бутер";
    form.add(form.original.catalog.get("ббр"), 30);
    var preview = form.preview();
    assertNull(preview.error());
    assertEquals("30", form.weight);
    assertEquals(70, preview.portionCalories(), 0);
    assertEquals(Fixtures.FOOD, form.original.foodText);
    form.changeWeight(0, 40);
    assertEquals(110, form.preview().portionCalories(), 0);
    assertEquals("хлеб 40 г<br>сыр 10 г", form.input().ingredients);
    form.remove(1);
    form.remove(0);
    assertFalse(form.recipe());
  }

  @Test
  public void barcodeKeepsManualFieldsAndDoesNotClearUnknownEnergy() {
    var form = new CatalogForm(Fixtures.catalog(), formFood());
    form.aliases = "мои алиасы";
    form.weight = "75";
    form.barcode("", null);
    assertEquals("хлеб", form.name);
    assertEquals("200", form.kcal100);
    assertEquals("75", form.weight);
    assertEquals("мои алиасы", form.aliases);
  }

  @Test
  public void missingBarcodeFieldsDoNotEraseTypedNewProduct() {
    PortionForm form = new PortionForm(Fixtures.day(), "Обед", null);
    form.startNew();
    form.name = "Моё название";
    form.kcal100 = "123";
    form.weight = "75";
    form.barcode("", null);
    assertEquals("Моё название", form.name);
    assertEquals("123", form.kcal100);
    assertEquals("75", form.weight);
  }

  private VaultMarkdown.Food formFood() {
    return Fixtures.catalog().catalog.get("хл");
  }
}
