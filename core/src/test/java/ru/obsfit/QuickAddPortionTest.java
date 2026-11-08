package ru.obsfit;

import static org.junit.Assert.*;

import org.junit.Test;

public class QuickAddPortionTest {
  private AppController choosing() {
    var controller = new AppController();
    controller.day(Fixtures.day());
    controller.portion("Обед", null);
    return controller;
  }

  @Test
  public void quickPortionUsesDefaultWeightAndPreservesVisibleDraftUntilSaveCompletes() {
    var controller = choosing();
    var visible = controller.state.portion;
    visible.query = "сыр";
    visible.weight = "500";
    visible.dirty = true;
    var before = visible.draft();
    var quick = controller.quickPortion(visible.original.catalog.get("ср"));
    assertEquals("20", quick.weight);
    assertEquals("Обед", quick.section);
    assertEquals(60, quick.total(), 0);
    assertSame(visible, controller.state.portion);
    assertEquals(before, visible.draft());
    assertEquals(AppState.Screen.PORTION, controller.state.screen);
    assertEquals(Fixtures.DAY, controller.state.day.day.source);
    assertNull(quick.save().newFood());
  }

  @Test
  public void quickRecipeUsesCommonExpansionAndRepositorySaveWithOneBackup() throws Exception {
    var controller = choosing();
    var original = controller.state.portion.original;
    var quick = controller.quickPortion(original.catalog.get("ббр"));
    var save = quick.save();
    var storage = new Fixtures.MemoryStorage();
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(storage, backups);
    repo.save(quick.original, save.noteText(), save.newFood());
    controller.day(repo.load(original.note));
    assertEquals(1, storage.writes);
    assertEquals(1, backups.saved.size());
    assertEquals(Fixtures.FOOD, storage.data.get("Еда.md"));
    assertEquals(2, controller.state.day.day.section("Обед").size());
    assertEquals(220, controller.state.day.day.total, 0);
    assertEquals(AppState.Screen.DAY, controller.state.screen);
    assertNull(controller.state.portion);
  }

  @Test
  public void quickAddKeepsSmallPositiveDefaultWeights() {
    String source =
        Fixtures.FOOD.replace(
            "<!-- food-table:end -->", "| вода | | 0 | 0.001 |\n<!-- food-table:end -->");
    var controller = new AppController();
    controller.day(Fixtures.day(Fixtures.DAY, source));
    controller.portion("Обед", null);
    var quick = controller.quickPortion(controller.state.day.catalog.get("вода"));
    assertEquals("0.001", quick.weight);
    var added =
        VaultMarkdown.day(quick.save().noteText(), quick.original.catalog).section("Обед").get(0);
    assertEquals(.001, added.weight, 0);
    assertEquals(0, added.kcal, 0);
  }

  @Test
  public void failedQuickSaveDoesNotLoseSearchOrOverwriteExternalChanges() throws Exception {
    var controller = choosing();
    var visible = controller.state.portion;
    visible.query = "ббр";
    var quick = controller.quickPortion(visible.original.catalog.get("ббр"));
    var save = quick.save();
    var storage = new Fixtures.MemoryStorage();
    storage.data.put(Fixtures.PATH, Fixtures.DAY + "Добавлено на ноутбуке\n");
    var repo = new VaultRepository(storage, new Fixtures.MemoryBackups());
    assertThrows(
        VaultRepository.StaleSnapshot.class,
        () -> repo.save(quick.original, save.noteText(), save.newFood()));
    assertEquals(0, storage.writes);
    assertSame(visible, controller.state.portion);
    assertEquals("ббр", visible.query);
    assertTrue(visible.choosing);
    assertTrue(storage.data.get(Fixtures.PATH).endsWith("Добавлено на ноутбуке\n"));
  }

  @Test
  public void tapStillSelectsEditablePortionAndDoesNotSaveOrQuickAdd() {
    var controller = choosing();
    var food = controller.state.portion.original.catalog.get("ббр");
    controller.chooseFood(food);
    assertEquals(AppState.Screen.PORTION, controller.state.screen);
    assertFalse(controller.state.portion.choosing);
    controller.state.portion.weight = "30";
    assertEquals(70, controller.state.portion.total(), 0);
    assertEquals(Fixtures.DAY, controller.state.day.day.source);
    assertThrows(IllegalStateException.class, () -> controller.quickPortion(food));
  }

  @Test
  public void editingAnExistingEntryAndStaleCatalogRowsCannotQuickAdd() {
    var controller = choosing();
    var otherSnapshotFood = Fixtures.catalog().catalog.get("ср");
    assertThrows(IllegalStateException.class, () -> controller.quickPortion(otherSnapshotFood));
    var day = controller.state.day;
    controller.portion("Заметки", day.day.entries.get(0));
    controller.chooseFromCatalog();
    assertThrows(IllegalStateException.class, () -> controller.quickPortion(day.catalog.get("ср")));
    controller.day(day);
    assertThrows(IllegalStateException.class, () -> controller.quickPortion(day.catalog.get("ср")));
  }
}
