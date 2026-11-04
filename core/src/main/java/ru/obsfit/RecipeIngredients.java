package ru.obsfit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.regex.*;

/** Shared recursive expansion of recipes for catalog ingredients and diary portions. */
public final class RecipeIngredients {
  private RecipeIngredients() {}

  public record Item(String name, double grams) {}

  public record Nutrition(double weight, double kcal100) {}

  public record Portion(String text, double calories) {}

  public static Nutrition nutrition(VaultMarkdown.Catalog catalog, List<Item> items) {
    double weight = 0, calories = 0;
    for (Item item : items) {
      VaultMarkdown.Food food = catalog.get(item.name());
      if (food == null)
        throw new IllegalArgumentException("Неизвестный ингредиент: " + item.name());
      double amount = grams(item.grams());
      weight += amount;
      calories += amount * food.kcal100 / 100;
    }
    grams(weight);
    if (!Double.isFinite(calories))
      throw new IllegalArgumentException("Некорректная калорийность блюда");
    return new Nutrition(weight, calories * 100 / weight);
  }

  private static final Pattern ROW =
      Pattern.compile(
          "^(.+?)\\s+(\\d+(?:[.,]\\d+)?)\\s*г$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  public static List<Item> parse(VaultMarkdown.Catalog catalog, String text) {
    List<Item> items = new ArrayList<>();
    for (String row : text.split("(?i)<br\\s*/?\\s*>|\\R")) {
      if (row.isBlank()) continue;
      Matcher match = ROW.matcher(row.trim());
      if (!match.matches()) throw new IllegalArgumentException("Некорректный ингредиент: " + row);
      VaultMarkdown.Food food = catalog.get(match.group(1));
      if (food == null)
        throw new IllegalArgumentException("Неизвестный ингредиент: " + match.group(1));
      items.add(new Item(food.name, grams(VaultMarkdown.number(match.group(2)))));
    }
    return items;
  }

  public static double grams(double value) {
    if (!Double.isFinite(value) || value <= 0 || value > 1_000_000_000)
      throw new IllegalArgumentException("Вес должен быть больше нуля");
    return value;
  }

  public static String toMarkdown(List<Item> items) {
    List<String> rows = new ArrayList<>();
    for (Item item : items) rows.add(item.name() + " " + weightText(item.grams()) + " г");
    return String.join("<br>", rows);
  }

  public static String weightText(double grams) {
    return BigDecimal.valueOf(grams)
        .round(new MathContext(15))
        .stripTrailingZeros()
        .toPlainString();
  }

  public static List<Item> flatten(VaultMarkdown.Catalog catalog, List<Item> items) {
    LinkedHashMap<String, Item> result = new LinkedHashMap<>();
    Set<String> visiting = new HashSet<>();
    for (Item item : items) expand(catalog, item, visiting, result);
    return new ArrayList<>(result.values());
  }

  private static void expand(
      VaultMarkdown.Catalog catalog, Item item, Set<String> visiting, Map<String, Item> result) {
    VaultMarkdown.Food food = catalog.get(item.name());
    if (food == null) throw new IllegalArgumentException("Неизвестный ингредиент: " + item.name());
    double amount = grams(item.grams());
    if (food.ingredients.isEmpty()) {
      merge(result, new Item(food.name, amount));
      return;
    }
    String key = VaultMarkdown.key(food.name);
    if (!visiting.add(key)) throw new IllegalArgumentException("Циклический состав: " + food.name);
    double factor = amount / grams(food.weight);
    for (Item child : parse(catalog, food.ingredients))
      expand(catalog, new Item(child.name(), grams(child.grams() * factor)), visiting, result);
    visiting.remove(key);
  }

  /** Rounded leaf portions, used by both the preview and the text written to the note. */
  public static Portion portion(VaultMarkdown.Catalog catalog, List<Item> items, double weight) {
    grams(weight);
    List<Item> leaves = flatten(catalog, items);
    double scale = weight / nutrition(catalog, leaves).weight();
    List<String> lines = new ArrayList<>();
    double total = 0;
    for (Item leaf : leaves) {
      double amount = grams(leaf.grams() * scale);
      double rounded = Math.round(amount * 100) / 100d;
      if (rounded > 0) amount = rounded;
      // Normalize the stored decimal before computing calories, including tiny positive weights.
      amount = VaultMarkdown.number(weightText(amount));
      VaultMarkdown.Food food = catalog.get(leaf.name());
      lines.add(VaultMarkdown.portion(food.name, amount, food.kcal100));
      total += Math.round(amount * food.kcal100 / 100);
    }
    return new Portion(String.join("\n", lines), total);
  }

  public static boolean available(
      VaultMarkdown.Catalog catalog, VaultMarkdown.Food food, String excludedName) {
    return excludedName == null
        || !dependsOn(catalog, food, VaultMarkdown.key(excludedName), new HashSet<>());
  }

  private static boolean dependsOn(
      VaultMarkdown.Catalog catalog, VaultMarkdown.Food food, String target, Set<String> visited) {
    String name = VaultMarkdown.key(food.name);
    if (name.equals(target)) return true;
    if (!visited.add(name)) return false;
    for (Item item : parse(catalog, food.ingredients))
      if (dependsOn(catalog, catalog.get(item.name()), target, visited)) return true;
    return false;
  }

  public static List<Item> add(
      VaultMarkdown.Catalog catalog,
      List<Item> current,
      String selectedName,
      double weight,
      String excludedName) {
    VaultMarkdown.Food selected = catalog.get(selectedName);
    if (selected == null) throw new IllegalArgumentException("Выберите продукт из справочника");
    weight = grams(weight);
    if (!available(catalog, selected, excludedName))
      throw new IllegalArgumentException("Продукт не может входить в собственный состав");
    List<Item> items = new ArrayList<>(current);
    items.add(new Item(selected.name, weight));
    return flatten(catalog, items);
  }

  private static void merge(Map<String, Item> values, Item item) {
    String key = VaultMarkdown.key(item.name());
    Item before = values.get(key);
    values.put(
        key, new Item(item.name(), grams((before == null ? 0 : before.grams()) + item.grams())));
  }
}
