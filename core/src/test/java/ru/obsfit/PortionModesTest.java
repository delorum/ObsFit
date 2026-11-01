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
    assertEquals("Перекусы", controller.state.portion.section);
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
}
