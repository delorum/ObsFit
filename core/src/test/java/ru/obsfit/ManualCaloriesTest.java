package ru.obsfit;

import static org.junit.Assert.*;

import org.junit.Test;

public class ManualCaloriesTest {
  private PortionForm manual(String description, String calories) {
    PortionForm form = new PortionForm(Fixtures.day(), "Обед", null);
    form.startManual();
    form.name = description;
    form.manualKcal = calories;
    return form;
  }

  @Test
  public void addsDescriptionAndCaloriesToSelectedSectionAndTotal() {
    var form = manual("Обед в кафе", "350");
    var save = form.save();
    var day = VaultMarkdown.day(save.noteText(), form.original.catalog);
    var entry = day.section("Обед").get(0);
    assertEquals("Обед в кафе", entry.name);
    assertEquals(350, entry.kcal, 0);
    assertNull(entry.weight);
    assertNull(entry.food);
    assertNull(save.newFood());
    assertEquals(430, day.total, 0);
    assertTrue(save.noteText().contains("СъеденоКалорий:: 430"));
  }

  @Test
  public void descriptionIsOptionalAndDecimalCommaIsAccepted() {
    var form = manual("", "123,5");
    String note = form.save().noteText();
    var entry = VaultMarkdown.day(note, form.original.catalog).section("Обед").get(0);
    assertEquals("", entry.name);
    assertEquals(123.5, entry.kcal, 0);
    assertTrue(note.contains("123.5 ккал"));
    assertTrue(note.contains("СъеденоКалорий:: 203.5"));
  }

  @Test
  public void explicitCaloriesDoNotDependOnFoodWithTheSameName() {
    var form = manual("хлеб", "350");
    var catalog = VaultMarkdown.catalog(Fixtures.FOOD.replace("200 | 40", "900 | 100"));
    var entry = VaultMarkdown.day(form.save().noteText(), catalog).section("Обед").get(0);
    assertEquals(350, entry.kcal, 0);
    assertNull(entry.food);
    assertNull(entry.weight);
  }

  @Test
  public void rejectsEmptyNegativeAndNonFiniteCaloriesButAllowsZero() {
    for (String invalid : new String[] {"", "-1", "NaN", "Infinity", "10 ккал"})
      assertThrows(IllegalArgumentException.class, () -> manual("обед", invalid).save());
    assertTrue(manual("", "0").save().noteText().contains("\n0 ккал\n"));
  }

  @Test
  public void descriptionCannotTurnIntoMarkdownOrWeightedPortion() {
    for (String invalid :
        new String[] {
          "хлеб 40 г",
          "хлеб 40г",
          "a\nb",
          "# заголовок",
          "a:: b",
          "`код`",
          "<!-- комментарий -->",
          "%% скрыто %%",
          "> цитата",
          "| строка таблицы"
        })
      assertThrows(invalid, IllegalArgumentException.class, () -> manual(invalid, "350").save());
  }

  @Test
  public void createdEntryCanBeEditedMovedAndDeleted() {
    var form = manual("ужин в кафе", "350");
    var original = Fixtures.day(form.save().noteText(), Fixtures.FOOD);
    var entry = original.day.section("Обед").get(0);
    var edit = new PortionForm(original, "Обед", entry);
    assertTrue(edit.manual);
    edit.name = "ужин";
    edit.manualKcal = "400";
    String changed = edit.save().noteText();
    var updated = VaultMarkdown.day(changed, original.catalog);
    assertEquals(480, updated.total, 0);
    String moved =
        VaultMarkdown.move(changed, updated.section("Обед").get(0), "Ужин", original.catalog);
    var afterMove = VaultMarkdown.day(moved, original.catalog);
    assertEquals(400, afterMove.section("Ужин").get(0).kcal, 0);
    assertEquals(480, afterMove.total, 0);
    String deleted =
        VaultMarkdown.edit(moved, afterMove.section("Ужин").get(0), null, original.catalog);
    assertEquals(80, VaultMarkdown.day(deleted, original.catalog).total, 0);
  }

  @Test
  public void savingManualCaloriesWritesOnlyDailyNote() throws Exception {
    var storage = new Fixtures.MemoryStorage();
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(storage, backups);
    var form = manual("неизвестная еда", "350");
    var save = form.save();
    repo.save(form.original, save.noteText(), save.newFood());
    assertEquals(1, storage.writes);
    assertEquals(Fixtures.FOOD, storage.data.get("Еда.md"));
    assertEquals(Fixtures.REPLACEMENTS, storage.data.get("Замены.md"));
    assertTrue(storage.data.get(Fixtures.PATH).contains("неизвестная еда 350 ккал"));
  }

  @Test
  public void manualDraftPreservesDescriptionAndAmountWithoutWeight() {
    var form = manual("кафе", "12,");
    var restored = PortionForm.restore(Fixtures.day(), form.draft());
    assertTrue(restored.manual);
    assertFalse(restored.choosing);
    assertFalse(restored.newFood);
    assertNull(restored.selectedFood);
    assertEquals("", restored.weight);
    assertEquals("кафе", restored.name);
    assertEquals("12,", restored.manualKcal);
    restored.manualKcal = "120";
    assertTrue(restored.save().noteText().contains("кафе 120 ккал"));
  }
}
