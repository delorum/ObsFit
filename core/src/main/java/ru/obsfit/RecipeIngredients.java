package ru.obsfit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.regex.*;

/** Ingredient selection preserves named foods, including nested recipes. */
public final class RecipeIngredients {
  private RecipeIngredients() {}

  public record Item(String name, double grams) {}

  public record Nutrition(double weight, double kcal100) {}

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
    for (Item item : items)
      rows.add(
          item.name()
              + " "
              + BigDecimal.valueOf(item.grams())
                  .round(new MathContext(15))
                  .stripTrailingZeros()
                  .toPlainString()
              + " г");
    return String.join("<br>", rows);
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
    LinkedHashMap<String, Item> merged = new LinkedHashMap<>();
    for (Item item : current) merge(merged, item);
    merge(merged, new Item(selected.name, weight));
    return new ArrayList<>(merged.values());
  }

  private static void merge(Map<String, Item> values, Item item) {
    String key = VaultMarkdown.key(item.name());
    Item before = values.get(key);
    values.put(
        key, new Item(item.name(), grams((before == null ? 0 : before.grams()) + item.grams())));
  }
}
