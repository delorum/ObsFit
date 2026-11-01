package ru.obsfit;

import static org.junit.Assert.*;

import java.io.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.Test;

public final class StateTest {
  private AppController controller() {
    var c = new AppController();
    c.restore(null, List.of(Fixtures.day().note), Fixtures.day(), Fixtures.catalog());
    return c;
  }

  private static AppState.Saved roundTrip(AppState.Saved value) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var out = new ObjectOutputStream(bytes)) {
      out.writeObject(value);
    }
    try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      return (AppState.Saved) in.readObject();
    }
  }

  @Test
  public void existingDatesSkipGapsAndDisableEdges() {
    var c = controller();
    c.state.notes =
        List.of(
            Fixtures.day().note,
            new VaultRepository.Note(
                LocalDate.of(2026, 11, 3), "Дневник/Хроника/2026-11-03-вт.md"));
    assertFalse(c.canNavigate(-1));
    assertTrue(c.canNavigate(1));
    c.navigate(1);
    assertEquals(LocalDate.of(2026, 11, 3), c.state.selected);
    assertFalse(c.canNavigate(1));
    c.navigate(1);
    assertEquals(LocalDate.of(2026, 11, 3), c.state.selected);
    c.navigate(-1);
    assertEquals(LocalDate.of(2026, 11, 1), c.state.selected);
  }

  @Test
  public void editorRoundTripPreservesPortionWeightAndUsesRenamedProduct() {
    var c = controller();
    c.portion("Обед", null);
    c.chooseFood(c.state.day.catalog.get("хл"));
    c.state.portion.weight = "75";
    c.state.portion.dirty = true;
    c.editCatalog(Fixtures.catalog(), c.state.catalog.catalog.get("хл"), true);
    assertEquals(AppState.Screen.CATALOG_EDITOR, c.state.screen);
    assertNull(c.state.portion);
    assertEquals("75", c.state.returnPortion.weight());
    String changed =
        VaultMarkdown.putFood(
            Fixtures.catalog().catalog,
            "хлеб",
            new VaultMarkdown.Food("белый хлеб", List.of("хл"), "", 250, 40),
            Fixtures.REPLACEMENTS);
    c.state.catalog = new VaultRepository.CatalogSnapshot(changed, Fixtures.REPLACEMENTS);
    c.returnFromCatalog(Fixtures.day(Fixtures.DAY, changed));
    assertEquals(AppState.Screen.PORTION, c.state.screen);
    assertEquals("75", c.state.portion.weight);
    assertEquals("белый хлеб", c.state.portion.name);
    assertEquals("250", c.state.portion.kcal100);
    assertEquals(188, c.state.portion.total(), 0);
    assertTrue(c.state.portion.dirty);
    assertTrue(c.state.portion.save().noteText().contains("белый хлеб 75 г 188 ккал"));
  }

  @Test
  public void cancelledEditorDoesNotAddPortionOrLoseDraft() {
    var c = controller();
    c.portion("Перекусы", null);
    c.chooseFood(c.state.day.catalog.get("ср"));
    c.state.portion.weight = "15";
    c.editCatalog(Fixtures.catalog(), c.state.catalog.catalog.get("ср"), true);
    c.returnFromCatalog(Fixtures.day());
    assertEquals("15", c.state.portion.weight);
    assertEquals(Fixtures.DAY, c.state.day.day.source);
    assertEquals("Перекусы", c.state.portion.section);
  }

  @Test
  public void changedDiaryEntryIsNeverReplacedByStaleDraft() {
    var c = controller();
    c.portion("Заметки", c.state.day.day.entries.get(0));
    c.editCatalog(Fixtures.catalog(), c.state.catalog.catalog.get("хл"), true);
    c.returnFromCatalog(
        Fixtures.day(
            Fixtures.DAY.replace("хлеб 40 г 80 ккал", "хлеб 50 г 100 ккал"), Fixtures.FOOD));
    assertEquals(AppState.Screen.DAY, c.state.screen);
    assertNull(c.state.portion);
    assertTrue(c.state.error.contains("изменилась"));
  }

  @Test
  public void cameraAndBothDraftsSurviveSerialization() throws Exception {
    var c = controller();
    c.portion("Завтрак", null);
    c.chooseFood(c.state.day.catalog.get("хл"));
    c.state.portion.weight = "65";
    c.editCatalog(Fixtures.catalog(), c.state.catalog.catalog.get("хл"), true);
    c.state.catalogForm.name = "Новый хлеб";
    c.state.catalogForm.dirty = true;
    c.state.catalogForm.ingredientsExpanded = true;
    c.queueBarcode(AppState.ScanTarget.CATALOG, "4620002683160");
    var restored = new AppController();
    restored.restore(roundTrip(c.state.save()), c.state.notes, Fixtures.day(), Fixtures.catalog());
    assertEquals(AppState.Screen.CATALOG_EDITOR, restored.state.screen);
    assertEquals("Новый хлеб", restored.state.catalogForm.name);
    assertEquals("65", restored.state.returnPortion.weight());
    assertTrue(restored.state.catalogForm.ingredientsExpanded);
    assertTrue(restored.barcodeReady());
    assertEquals("4620002683160", restored.state.pendingBarcode);
    Object recipient = restored.barcodeRecipient();
    assertTrue(restored.acceptBarcode(recipient, "4620002683160", "Название OFF", 339d));
    assertEquals("339", restored.state.catalogForm.kcal100);
    assertNull(restored.state.pendingBarcode);
  }

  @Test
  public void resultArrivingBeforeVaultReconnectIsRetained() throws Exception {
    var old = controller();
    old.portion("Обед", null);
    old.newFood();
    var saved = roundTrip(old.state.save());
    var c = new AppController();
    c.queueBarcode(AppState.ScanTarget.PORTION, "12345678");
    assertFalse(c.barcodeReady());
    c.restore(saved, old.state.notes, Fixtures.day(), Fixtures.catalog());
    assertTrue(c.barcodeReady());
    assertEquals("12345678", c.state.pendingBarcode);
    assertTrue(c.acceptBarcode(c.barcodeRecipient(), "12345678", "Каша", 150d));
    assertEquals("Каша", c.state.portion.name);
  }

  @Test
  public void staleBarcodeCannotChangeAnotherProduct() {
    var c = controller();
    c.portion("Обед", null);
    c.newFood();
    c.queueBarcode(AppState.ScanTarget.PORTION, "12345678");
    Object old = c.barcodeRecipient();
    c.chooseFood(c.state.day.catalog.get("ср"));
    assertFalse(c.acceptBarcode(old, "12345678", "другое", 999d));
    assertEquals("сыр", c.state.portion.name);
    assertEquals("300", c.state.portion.kcal100);
  }

  @Test
  public void barcodeDoesNotReplaceRecipeCalories() {
    var c = controller();
    c.editCatalog(Fixtures.catalog(), c.state.catalog.catalog.get("ббр"), false);
    String before = c.state.catalogForm.kcal100;
    c.queueBarcode(AppState.ScanTarget.CATALOG, "12345678");
    assertTrue(c.acceptBarcode(c.barcodeRecipient(), "12345678", "Новое имя", 900d));
    assertEquals(before, c.state.catalogForm.kcal100);
    assertEquals(2, c.state.catalogForm.ingredients.size());
  }

  @Test
  public void savingThenRecreatingDuringReturnRestoresPortion() throws Exception {
    var c = controller();
    c.portion("Ужин", null);
    c.chooseFood(c.state.day.catalog.get("хл"));
    c.state.portion.weight = "85";
    c.editCatalog(Fixtures.catalog(), c.state.catalog.catalog.get("хл"), true);
    c.state.catalogForm = null;
    var restored = new AppController();
    restored.restore(roundTrip(c.state.save()), c.state.notes, Fixtures.day(), Fixtures.catalog());
    assertEquals(AppState.Screen.PORTION, restored.state.screen);
    assertEquals("85", restored.state.portion.weight);
  }

  @Test
  public void externalCatalogChangeDoesNotSilentlyOverwriteEditor() throws Exception {
    var c = controller();
    c.editCatalog(Fixtures.catalog(), c.state.catalog.catalog.get("хл"), false);
    c.state.catalogForm.name = "Черновик";
    var saved = roundTrip(c.state.save());
    var changed =
        new VaultRepository.CatalogSnapshot(
            Fixtures.FOOD.replace("| 200 | 40 |", "| 250 | 40 |"), Fixtures.REPLACEMENTS);
    var restored = new AppController();
    restored.restore(saved, c.state.notes, Fixtures.day(), changed);
    assertEquals(AppState.Screen.CATALOG, restored.state.screen);
    assertNull(restored.state.catalogForm);
    assertTrue(restored.state.error.contains("изменился"));
  }

  @Test
  public void emptyVaultCanOpenCatalogWithoutDailyNotes() {
    var c = new AppController();
    c.restore(null, List.of(), null, Fixtures.catalog());
    c.catalog(Fixtures.catalog());
    c.editCatalog(Fixtures.catalog(), null, false);
    assertEquals(AppState.Screen.CATALOG_EDITOR, c.state.screen);
    assertNull(c.currentNote());
  }

  @Test
  public void collapsedSectionsAndQueriesSurviveRestore() throws Exception {
    var c = controller();
    c.state.expanded.remove("Обед");
    c.state.catalogQuery = "хл";
    var restored = new AppController();
    restored.restore(roundTrip(c.state.save()), c.state.notes, Fixtures.day(), Fixtures.catalog());
    assertFalse(restored.state.expanded.contains("Обед"));
    assertEquals("хл", restored.state.catalogQuery);
  }

  @Test
  public void missingDayDuringRestoreGoesToDayWithError() throws Exception {
    var c = controller();
    c.portion("Завтрак", c.state.day.day.entries.get(0));
    var restored = new AppController();
    restored.restore(roundTrip(c.state.save()), List.of(), null, Fixtures.catalog());
    assertEquals(AppState.Screen.DAY, restored.state.screen);
    assertTrue(restored.state.error.contains("не найдена"));
  }
}
