package ru.obsfit;

import static org.junit.Assert.*;

import org.junit.Test;

public final class PortionModesTest {
  @Test
  public void switchesAllThreeModesWithoutWritingOrAddingAPortion() {
    AppController controller = new AppController();
    var original = Fixtures.day();
    controller.day(original);
    controller.portion("Перекусы", null);
    assertTrue(controller.state.portion.choosing);
    controller.newFood();
    assertTrue(controller.state.portion.newFood);
    assertFalse(controller.state.portion.manual);
    controller.manualCalories();
    assertTrue(controller.state.portion.manual);
    assertFalse(controller.state.portion.newFood);
    assertFalse(controller.state.portion.choosing);
    assertNull(controller.state.portion.selectedFood);
    assertSame(original, controller.state.day);
    assertEquals("Перекус 4", controller.state.portion.section);
    controller.chooseFromCatalog();
    assertTrue(controller.state.portion.choosing);
    assertFalse(controller.state.portion.manual);
    assertFalse(controller.state.portion.newFood);
    controller.manualCalories();
    controller.newFood();
    assertTrue(controller.state.portion.newFood);
    assertFalse(controller.state.portion.manual);
  }

  @Test
  public void inlineManualDraftRestoresAndSavesOnlyWhenConfirmed() {
    AppController controller = new AppController();
    controller.day(Fixtures.day());
    controller.portion("Ужин", null);
    controller.manualCalories();
    controller.state.portion.name = "кафе";
    controller.state.portion.manualKcal = "350";
    var restored = PortionForm.restore(Fixtures.day(), controller.state.portion.draft());
    assertTrue(restored.manual);
    assertFalse(restored.choosing);
    assertNull(restored.save().newFood());
    assertTrue(restored.save().noteText().contains("кафе 350 ккал"));
    assertFalse(controller.state.day.day.source.contains("кафе"));
    assertEquals(
        "Ужин",
        VaultMarkdown.day(restored.save().noteText(), restored.original.catalog)
            .entries
            .get(1)
            .section);
  }

  @Test
  public void editingExistingFoodHasNoModeSwitchesButWeightAndHistoryStayAvailable() {
    var original = Fixtures.day();
    var controller = new AppController();
    controller.day(original);
    controller.portion("Заметки", original.day.entries.get(0));
    assertFalse(controller.state.portion.modeSwitchingAllowed());
    assertFalse(controller.state.portion.choosing);
    var food = controller.state.portion.selectedFood;
    controller.state.portion.changePortionWeight("50");
    var restored = PortionForm.restore(original, controller.state.portion.draft());
    assertFalse(restored.modeSwitchingAllowed());
    assertEquals(food.name, restored.selectedFood.name);
    assertEquals(100, restored.total(), 0);
    assertTrue(restored.save().noteText().contains("хлеб 50 г 100 ккал"));
    controller.foodHistory(0);
    assertSame(food, controller.state.foodHistory.food);
  }

  @Test
  public void manualEntryEditingKeepsDescriptionAndCaloriesWithoutProductModeButtons() {
    var original = Fixtures.day(Fixtures.DAY + "кафе 100 ккал\n", Fixtures.FOOD);
    var form = new PortionForm(original, "Заметки", original.day.entries.get(1));
    assertFalse(form.modeSwitchingAllowed());
    assertTrue(form.manual);
    form.manualKcal = "150";
    assertTrue(form.save().noteText().contains("кафе 150 ккал"));
    assertNull(form.save().newFood());
    assertTrue(new PortionForm(original, "Обед", null).modeSwitchingAllowed());
  }
}
