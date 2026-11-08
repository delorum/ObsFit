package ru.obsfit;

import static org.junit.Assert.*;
import static ru.obsfit.CalculatorForm.Target.*;

import java.io.*;
import java.util.*;
import org.junit.Test;

public class CalculatorTest {
  private AppController portion(String name) {
    var c = new AppController();
    c.day(Fixtures.day());
    c.portion("Обед", null);
    c.chooseFood(c.state.day.catalog.get(name));
    return c;
  }

  private void open(AppController c, CalculatorForm.Target target) {
    c.calculator(target, -1, null, null, 120);
  }

  private void apply(AppController c, String expression) {
    c.state.calculator.expression = expression;
    c.applyCalculator();
  }

  // Simulate the existing ingredient weight dialog's explicit Apply after receiving the value.
  private void confirmIngredient(AppController c) {
    var returned = c.state.calculator;
    assertNotNull(returned);
    assertEquals(returned.target.source(), c.state.screen);
    var form =
        returned.target == CATALOG_INGREDIENT ? c.state.catalogForm : c.state.portion.composition;
    double value = VaultMarkdown.number(returned.expression);
    if (returned.ingredientIndex >= 0) form.changeWeight(returned.ingredientIndex, value);
    else form.add(form.original.catalog.get(returned.ingredientName), value);
    if (returned.target == PORTION_INGREDIENT) c.state.portion.compositionChanged();
    c.state.calculator = null;
  }

  @Test
  public void respectsPrecedenceParenthesesAndUnarySigns() {
    assertEquals("14", Calculator.evaluate("2+3*4"));
    assertEquals("20", Calculator.evaluate("(2+3)*4"));
    assertEquals("1", Calculator.evaluate("8/4/2"));
    assertEquals("-5", Calculator.evaluate("2-3-4"));
    assertEquals("6", Calculator.evaluate("-2*-3"));
    assertEquals("-8", Calculator.evaluate("-(2+2)*2"));
    assertEquals("5", Calculator.evaluate("2++3"));
  }

  @Test
  public void decimalsUseExactArithmeticAndSupportBothSeparators() {
    assertEquals("0.3", Calculator.evaluate("0.1+0.2"));
    assertEquals("3", Calculator.evaluate(" 1,5 + .5 * 3 "));
    assertEquals("0.333333333333333", Calculator.evaluate("1/3"));
    assertEquals("12.5", Calculator.evaluate("(100-25)/6"));
  }

  @Test
  public void invalidAndIncompleteExpressionsNeverReturnPartialValues() {
    for (String raw :
        List.of(
            "", ".", "2+", "(2+3", "2)", "2(3)", "1..2", "1,2.3", "hello", "2^3", "1/0", "1/(2-2)"))
      assertThrows(raw, IllegalArgumentException.class, () -> Calculator.evaluate(raw));
    assertThrows(IllegalArgumentException.class, () -> Calculator.evaluate("1".repeat(513)));
    assertThrows(
        IllegalArgumentException.class,
        () -> Calculator.evaluate("(".repeat(80) + "1" + ")".repeat(80)));
  }

  @Test
  public void buttonSequenceSupportsBackspaceClearEqualsAndContinuingWithResult() {
    var form = new CalculatorForm(PORTION_WEIGHT, -1, null, "", 0);
    for (String key : List.of("(", "2", "+", "3", ")", "*", "4")) form.press(key);
    assertEquals("20", form.preview().value());
    form.press("=");
    assertEquals("20", form.expression);
    for (String key : List.of("/", "2")) form.press(key);
    assertEquals("10", form.result());
    form.press("⌫");
    assertEquals("20/", form.expression);
    assertNull(form.preview().value());
    form.press("=");
    assertEquals("20/", form.expression);
    form.press("C");
    form.press("⌫");
    assertEquals("", form.expression);
    assertThrows(IllegalArgumentException.class, () -> form.press("paste"));
  }

  @Test
  public void fieldConstraintsDoNotPreventIntermediateNegativeCalculations() {
    var weight = new CalculatorForm(PORTION_WEIGHT, -1, null, "2-5", 0);
    assertEquals("-3", weight.preview().value());
    assertNotNull(weight.applicationError());
    assertThrows(IllegalArgumentException.class, weight::result);
    weight.press("+");
    weight.press("5");
    assertEquals("2", weight.result());
    weight.expression = "0";
    assertNotNull(weight.applicationError());
    var kcal = new CalculatorForm(PORTION_CALORIES, -1, null, "0", 0);
    assertEquals("0", kcal.result());
    kcal.expression = "-1";
    assertNotNull(kcal.applicationError());
    kcal.expression = "1000000001";
    assertNotNull(kcal.applicationError());
  }

  @Test
  public void appliesToSelectedPortionWithoutSavingOrTouchingCatalog() {
    var c = portion("хл");
    var source = c.state.day.day.source;
    open(c, PORTION_WEIGHT);
    assertEquals("40", c.state.calculator.expression);
    assertEquals(AppState.Screen.CALCULATOR, c.state.screen);
    apply(c, "(100-25)/2");
    assertEquals("37.5", c.state.portion.weight);
    assertEquals(AppState.Screen.PORTION, c.state.screen);
    assertNull(c.state.calculator);
    assertTrue(c.state.portion.dirty);
    assertEquals(source, c.state.day.day.source);
    assertEquals(Fixtures.FOOD, c.state.day.foodText);
  }

  @Test
  public void cancelPreservesSourceValueAndDraftState() {
    var c = portion("хл");
    open(c, PORTION_WEIGHT);
    c.state.calculator.expression = "200*3";
    c.cancelCalculator();
    assertEquals("40", c.state.portion.weight);
    assertFalse(c.state.portion.dirty);
    assertEquals(AppState.Screen.PORTION, c.state.screen);
  }

  @Test
  public void invalidApplicationKeepsCalculatorAndOriginalFieldOpen() {
    var c = portion("хл");
    open(c, PORTION_WEIGHT);
    c.state.calculator.expression = "1/0";
    assertThrows(IllegalArgumentException.class, c::applyCalculator);
    assertEquals(AppState.Screen.CALCULATOR, c.state.screen);
    assertEquals("40", c.state.portion.weight);
  }

  @Test
  public void readOnlyAndWrongModeFieldsCannotBeEditedThroughCalculator() {
    var c = portion("ббр");
    assertThrows(IllegalStateException.class, () -> open(c, PORTION_KCAL100));
    assertThrows(IllegalStateException.class, () -> open(c, PORTION_DEFAULT_WEIGHT));
    assertThrows(IllegalStateException.class, () -> open(c, PORTION_CALORIES));
    assertEquals(AppState.Screen.PORTION, c.state.screen);
    assertNull(c.state.calculator);
    c.editCatalog(Fixtures.catalog(), Fixtures.catalog().catalog.get("ббр"), false);
    assertThrows(IllegalStateException.class, () -> open(c, CATALOG_WEIGHT));
    assertThrows(IllegalStateException.class, () -> open(c, CATALOG_KCAL100));
  }

  @Test
  public void appliesAllEditablePortionAndCatalogFields() {
    var c = portion("хл");
    c.newFood();
    open(c, PORTION_DEFAULT_WEIGHT);
    apply(c, "25*2");
    assertEquals("50", c.state.portion.defaultWeight);
    open(c, PORTION_KCAL100);
    apply(c, "100+20");
    assertEquals("120", c.state.portion.kcal100);
    c.manualCalories();
    open(c, PORTION_CALORIES);
    apply(c, "3*25");
    assertEquals("75", c.state.portion.manualKcal);
    c.editCatalog(Fixtures.catalog(), null, false);
    open(c, CATALOG_WEIGHT);
    apply(c, "200/2");
    assertEquals("100", c.state.catalogForm.weight);
    open(c, CATALOG_KCAL100);
    apply(c, "50+25");
    assertEquals("75", c.state.catalogForm.kcal100);
    assertTrue(c.state.catalogForm.dirty);
  }

  @Test
  public void recipeWeightResultScalesIngredientsAndPreviewTogether() {
    var c = portion("ббр");
    open(c, PORTION_WEIGHT);
    apply(c, "60/2");
    assertEquals("30", c.state.portion.weight);
    assertEquals(70, c.state.portion.total(), 0);
    assertTrue(c.state.portion.save().noteText().contains("хлеб 20 г 40 ккал\nсыр 10 г 30 ккал"));
  }

  @Test
  public void existingIngredientResultChangesOnlyItsWeightAndPortionDraft() {
    var c = portion("ббр");
    c.calculator(PORTION_INGREDIENT, 1, null, "20", 80);
    apply(c, "20-5");
    assertEquals(20, c.state.portion.composition.ingredients.get(1).grams(), 0);
    assertEquals("15", c.state.calculator.expression);
    confirmIngredient(c);
    assertEquals("55", c.state.portion.weight);
    assertEquals(15, c.state.portion.composition.ingredients.get(1).grams(), 0);
    assertEquals(40, c.state.portion.composition.ingredients.get(0).grams(), 0);
    assertEquals(Fixtures.FOOD, c.state.day.foodText);
  }

  @Test
  public void pendingIngredientCanBeCalculatedBeforeItIsAdded() {
    var c = portion("хл");
    c.newFood();
    c.calculator(PORTION_INGREDIENT, -1, "бутерброд", "60", 90);
    assertTrue(c.state.portion.composition.ingredients.isEmpty());
    apply(c, "60/2");
    assertTrue(c.state.portion.composition.ingredients.isEmpty());
    confirmIngredient(c);
    assertEquals(2, c.state.portion.composition.ingredients.size());
    assertEquals("30", c.state.portion.weight);
    assertEquals(70, c.state.portion.total(), 0);
  }

  @Test
  public void cancelPendingIngredientDoesNotAddIt() {
    var c = portion("хл");
    c.newFood();
    c.calculator(PORTION_INGREDIENT, -1, "сыр", "20", 0);
    c.state.calculator.expression = "20*2";
    c.cancelCalculator();
    assertTrue(c.state.portion.composition.ingredients.isEmpty());
  }

  @Test
  public void catalogIngredientAndDependencyValidationRemainInCatalogForm() {
    var c = new AppController();
    c.editCatalog(Fixtures.catalog(), null, false);
    c.calculator(CATALOG_INGREDIENT, -1, "хлеб", "40", 0);
    apply(c, "40/2");
    confirmIngredient(c);
    assertEquals(20, c.state.catalogForm.ingredients.get(0).grams(), 0);
    c.calculator(CATALOG_INGREDIENT, 0, null, "20", 0);
    apply(c, "20+10");
    confirmIngredient(c);
    assertEquals(30, c.state.catalogForm.ingredients.get(0).grams(), 0);
    c.editCatalog(Fixtures.catalog(), Fixtures.catalog().catalog.get("ббр"), false);
    assertThrows(
        IllegalStateException.class,
        () -> c.calculator(CATALOG_INGREDIENT, -1, "бутерброд", "60", 0));
  }

  private static AppState.Saved serialise(AppState.Saved saved) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var out = new ObjectOutputStream(bytes)) {
      out.writeObject(saved);
    }
    try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      return (AppState.Saved) in.readObject();
    }
  }

  @Test
  public void calculatorAndUnderlyingPortionSurviveSerializationAndRestore() throws Exception {
    var c = portion("ббр");
    c.state.portion.query = "бб";
    open(c, PORTION_WEIGHT);
    c.state.calculator.expression = "(60-10)/2";
    var restored = new AppController();
    restored.restore(
        serialise(c.state.save()),
        List.of(Fixtures.day().note),
        Fixtures.day(),
        Fixtures.catalog());
    assertEquals(AppState.Screen.CALCULATOR, restored.state.screen);
    assertEquals("(60-10)/2", restored.state.calculator.expression);
    assertEquals(120, restored.state.calculator.scrollY);
    assertEquals("бб", restored.state.portion.query);
    restored.applyCalculator();
    assertEquals("25", restored.state.portion.weight);
  }

  @Test
  public void calculatorCatalogDraftAndReturnPortionSurviveRestore() throws Exception {
    var c = portion("хл");
    c.editCatalog(Fixtures.catalog(), c.state.day.catalog.get("хл"), true);
    open(c, CATALOG_KCAL100);
    c.state.calculator.expression = "200+25";
    var restored = new AppController();
    restored.restore(
        serialise(c.state.save()),
        List.of(Fixtures.day().note),
        Fixtures.day(),
        Fixtures.catalog());
    assertEquals(AppState.Screen.CALCULATOR, restored.state.screen);
    assertNotNull(restored.state.returnPortion);
    restored.applyCalculator();
    assertEquals("225", restored.state.catalogForm.kcal100);
    assertEquals(AppState.Screen.CATALOG_EDITOR, restored.state.screen);
  }

  @Test
  public void pendingIngredientCalculationSurvivesRestoreWithoutPrematureAddition()
      throws Exception {
    var c = portion("хл");
    c.newFood();
    c.calculator(PORTION_INGREDIENT, -1, "сыр", "20", 45);
    c.state.calculator.expression = "20/2";
    var restored = new AppController();
    restored.restore(
        serialise(c.state.save()),
        List.of(Fixtures.day().note),
        Fixtures.day(),
        Fixtures.catalog());
    assertEquals(AppState.Screen.CALCULATOR, restored.state.screen);
    restored.applyCalculator();
    assertEquals("10", restored.state.calculator.expression);
    assertTrue(restored.state.portion.composition.ingredients.isEmpty());
    confirmIngredient(restored);
    assertEquals(10, restored.state.portion.composition.ingredients.get(0).grams(), 0);
  }

  @Test
  public void staleUnderlyingEntryCannotReceiveAResultAfterRestore() throws Exception {
    var c = new AppController();
    c.day(Fixtures.day());
    c.portion("Заметки", c.state.day.day.entries.get(0));
    open(c, PORTION_WEIGHT);
    var changed =
        Fixtures.day(Fixtures.DAY.replace("40 г 80 ккал", "50 г 100 ккал"), Fixtures.FOOD);
    var restored = new AppController();
    restored.restore(serialise(c.state.save()), List.of(changed.note), changed, Fixtures.catalog());
    assertEquals(AppState.Screen.DAY, restored.state.screen);
    assertNull(restored.state.calculator);
    assertNotNull(restored.state.error);
  }
}
