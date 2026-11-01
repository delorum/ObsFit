import java.util.*;
import ru.obsfit.*;

public final class RecipeIngredientsTest {
  static int checks;

  static void equal(Object e, Object a) {
    checks++;
    if (!Objects.equals(e, a)) throw new AssertionError("Expected " + e + ", got " + a);
  }

  static void near(double e, double a) {
    checks++;
    if (Math.abs(e - a) > .001) throw new AssertionError("Expected " + e + ", got " + a);
  }

  static void rejects(Runnable r) {
    checks++;
    try {
      r.run();
    } catch (IllegalArgumentException ok) {
      return;
    }
    throw new AssertionError("Expected rejection");
  }

  public static void main(String[] args) {
    String table =
        "<!-- food-table:start -->\n"
            + "| Название | Состав | Ккал | Вес |\n"
            + "| --- | --- | --- | --- |\n"
            + "| белый хлеб<br>бт | | 200 | 40 |\n"
            + "| колбаса<br>клб | | 300 | 20 |\n"
            + "| бутерброд<br>ббр | бт 40 г<br>клб 20 г | 233.33 | 60 |\n"
            + "<!-- food-table:end -->";
    var catalog = VaultMarkdown.catalog(table);
    var parsed = RecipeIngredients.parse(catalog, "бт 20 г<br>КЛБ 10,5 г");
    equal(2, parsed.size());
    equal("белый хлеб", parsed.get(0).name());
    near(10.5, parsed.get(1).grams());
    equal("белый хлеб 20 г<br>колбаса 10.5 г", RecipeIngredients.toMarkdown(parsed));
    equal(parsed, RecipeIngredients.parse(catalog, RecipeIngredients.toMarkdown(parsed)));
    var base = RecipeIngredients.add(catalog, List.of(), "бт", 20, null);
    equal("белый хлеб 20 г", RecipeIngredients.toMarkdown(base));
    var combined = RecipeIngredients.add(catalog, base, "ббр", 30, null);
    equal(2, combined.size());
    near(40, combined.get(0).grams());
    near(10, combined.get(1).grams());
    equal("белый хлеб 20 г", RecipeIngredients.toMarkdown(base));
    var doublePortion = RecipeIngredients.add(catalog, List.of(), "бутерброд", 120, null);
    near(80, doublePortion.get(0).grams());
    near(40, doublePortion.get(1).grams());
    var input =
        new VaultMarkdown.Food(
            "новый бутер", List.of("нб"), RecipeIngredients.toMarkdown(combined), 0, 0);
    String saved = VaultMarkdown.putFood(catalog, null, input, "---\n");
    var result = VaultMarkdown.catalog(saved).get("нб");
    near(50, result.weight);
    near(220, result.kcal100);
    equal(false, RecipeIngredients.available(catalog, catalog.get("бт"), "белый хлеб"));
    equal(false, RecipeIngredients.available(catalog, catalog.get("ббр"), "белый хлеб"));
    equal(true, RecipeIngredients.available(catalog, catalog.get("клб"), "белый хлеб"));
    equal(true, RecipeIngredients.available(catalog, catalog.get("ббр"), null));
    rejects(() -> RecipeIngredients.add(catalog, List.of(), "ббр", 30, "белый хлеб"));
    rejects(() -> RecipeIngredients.add(catalog, List.of(), "нет", 20, null));
    rejects(() -> RecipeIngredients.add(catalog, List.of(), "бт", 0, null));
    rejects(() -> RecipeIngredients.grams(-1));
    rejects(() -> RecipeIngredients.grams(Double.NaN));
    rejects(() -> RecipeIngredients.grams(Double.POSITIVE_INFINITY));
    near(.001, RecipeIngredients.grams(.001));
    near(12.345, RecipeIngredients.grams(12.345));
    rejects(() -> RecipeIngredients.parse(catalog, "ббр 20 г"));
    rejects(() -> RecipeIngredients.parse(catalog, "нет 20 г"));
    var restored = RecipeIngredients.parse(catalog, "бт 20 г\nклб 10 г");
    equal(2, restored.size());
    var edited = new ArrayList<>(restored);
    edited.set(0, new RecipeIngredients.Item("белый хлеб", 60));
    edited.remove(1);
    equal("белый хлеб 60 г", RecipeIngredients.toMarkdown(edited));
    equal(2, restored.size());
    var empty = new ArrayList<>(edited);
    empty.clear();
    equal("", RecipeIngredients.toMarkdown(empty));
    equal(0, RecipeIngredients.parse(catalog, "").size());
    equal(
        "колбаса 0.001 г",
        RecipeIngredients.toMarkdown(RecipeIngredients.parse(catalog, "клб 0.001 г")));
    var tiny =
        RecipeIngredients.add(
            catalog, List.of(new RecipeIngredients.Item("колбаса", .1)), "клб", .2, null);
    equal("колбаса 0.3 г", RecipeIngredients.toMarkdown(tiny));
    System.out.println("ObsFit ingredient selection: " + checks + " checks passed");
  }
}
