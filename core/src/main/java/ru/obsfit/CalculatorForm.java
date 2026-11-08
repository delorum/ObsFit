package ru.obsfit;

import java.io.Serializable;

/** A calculation and a typed return destination; no widget references or callbacks are retained. */
public final class CalculatorForm {
  public enum Target {
    PORTION_WEIGHT,
    PORTION_DEFAULT_WEIGHT,
    PORTION_KCAL100,
    PORTION_CALORIES,
    CATALOG_WEIGHT,
    CATALOG_KCAL100,
    PORTION_INGREDIENT,
    CATALOG_INGREDIENT;

    public AppState.Screen source() {
      return switch (this) {
        case CATALOG_WEIGHT, CATALOG_KCAL100, CATALOG_INGREDIENT -> AppState.Screen.CATALOG_EDITOR;
        default -> AppState.Screen.PORTION;
      };
    }

    public boolean calories() {
      return this == PORTION_KCAL100 || this == PORTION_CALORIES || this == CATALOG_KCAL100;
    }

    public boolean ingredient() {
      return this == PORTION_INGREDIENT || this == CATALOG_INGREDIENT;
    }
  }

  public record Draft(
      Target target, int ingredientIndex, String ingredientName, String expression, int scrollY)
      implements Serializable {}

  public record Preview(String value, String error) {}

  public final Target target;
  public final int ingredientIndex, scrollY;
  public final String ingredientName;
  public String expression;

  public CalculatorForm(Target target, int index, String name, String expression, int scrollY) {
    this.target = target;
    ingredientIndex = index;
    ingredientName = name;
    this.expression = expression;
    this.scrollY = scrollY;
  }

  public void press(String key) {
    switch (key) {
      case "C" -> expression = "";
      case "⌫" -> {
        if (!expression.isEmpty()) expression = expression.substring(0, expression.length() - 1);
      }
      case "=" -> {
        var preview = preview();
        if (preview.value != null) expression = preview.value;
      }
      default -> {
        if (key.length() != 1 || "0123456789+-*/().,".indexOf(key.charAt(0)) < 0)
          throw new IllegalArgumentException("Неизвестная кнопка");
        if (expression.length() < 512) expression += key;
      }
    }
  }

  public Preview preview() {
    try {
      return new Preview(Calculator.evaluate(expression), null);
    } catch (IllegalArgumentException error) {
      return new Preview(null, error.getMessage());
    }
  }

  public String applicationError() {
    var preview = preview();
    if (preview.value == null) return preview.error;
    double value = Double.parseDouble(preview.value);
    if (value < 0 || value == 0 && !target.calories())
      return target.calories()
          ? "Калории не могут быть отрицательными"
          : "Вес должен быть больше нуля";
    return value > 1_000_000_000 ? "Слишком большое значение для поля" : null;
  }

  public String result() {
    String error = applicationError();
    if (error != null) throw new IllegalArgumentException(error);
    return preview().value;
  }

  public Draft draft() {
    return new Draft(target, ingredientIndex, ingredientName, expression, scrollY);
  }

  public static CalculatorForm restore(Draft draft) {
    return new CalculatorForm(
        draft.target, draft.ingredientIndex, draft.ingredientName, draft.expression, draft.scrollY);
  }
}
