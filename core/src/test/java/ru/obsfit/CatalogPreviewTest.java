package ru.obsfit;

import static org.junit.Assert.*;

import org.junit.Test;

public class CatalogPreviewTest {
  private CatalogForm recipe() {
    var form = new CatalogForm(Fixtures.catalog(), null);
    form.add(form.original.catalog.get("хлеб"), 40);
    form.add(form.original.catalog.get("сыр"), 20);
    return form;
  }

  @Test
  public void newRecipeHasPortionCaloriesBeforeNameIsEntered() {
    var form = recipe();
    var preview = form.preview();
    assertEquals(140, preview.portionCalories(), 0);
    assertNotNull(preview.error());
    assertEquals("60", form.weight);
    assertEquals("233.33", form.kcal100);
    assertThrows(IllegalArgumentException.class, form::save);
  }

  @Test
  public void unnamedRecipeWeightEditsAndRemovalUpdateNutritionImmediately() {
    var form = recipe();
    form.changeWeight(0, 20);
    assertEquals(100, form.preview().portionCalories(), 0);
    assertEquals("40", form.weight);
    form.remove(1);
    assertEquals(40, form.preview().portionCalories(), 0);
    assertEquals("20", form.weight);
    assertEquals("200", form.kcal100);
    form.name = "Новая порция";
    assertNull(form.preview().error());
    assertEquals(40, form.preview().portionCalories(), 0);
  }

  @Test
  public void existingAndNewRecipeUseSamePreviewEvenWithEmptyNames() {
    var catalog = Fixtures.catalog();
    var existing = new CatalogForm(catalog, catalog.catalog.get("бутерброд"));
    existing.name = "";
    assertEquals(recipe().preview().portionCalories(), existing.preview().portionCalories());
    assertNotNull(existing.preview().error());
  }

  @Test
  public void simpleProductAlsoShowsPortionAndZeroCaloriesWithoutName() {
    var form = new CatalogForm(Fixtures.catalog(), null);
    form.weight = "50";
    form.kcal100 = "123";
    assertEquals(62, form.preview().portionCalories(), 0);
    assertNotNull(form.preview().error());
    form.kcal100 = "0";
    assertEquals(0, form.preview().portionCalories(), 0);
    form.name = "Новый напиток";
    assertNull(form.preview().error());
    form.kcal100 = "";
    assertNull(form.preview().portionCalories());
    assertNotNull(form.preview().error());
  }

  @Test
  public void invalidOrDuplicateNameKeepsNutritionVisibleButCannotSave() {
    var form = recipe();
    for (String name : new String[] {"хлеб", "ббр", "# неверное имя"}) {
      form.name = name;
      assertEquals(140, form.preview().portionCalories(), 0);
      assertNotNull(form.preview().error());
      assertThrows(IllegalArgumentException.class, form::save);
    }
    assertEquals(Fixtures.FOOD, form.original.foodText);
  }

  @Test
  public void unresolvedImportAndInvalidAmountsDoNotShowMisleadingTotals() {
    var form = recipe();
    form.name = "Новый рецепт";
    form.unresolved.add("неизвестная еда 100 ккал");
    assertNull(form.preview().portionCalories());
    assertNotNull(form.preview().error());
    var simple = new CatalogForm(Fixtures.catalog(), null);
    simple.kcal100 = "100";
    simple.weight = "0";
    assertNull(simple.preview().portionCalories());
    assertNotNull(simple.preview().error());
  }
}
