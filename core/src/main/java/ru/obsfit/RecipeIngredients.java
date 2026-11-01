package ru.obsfit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.regex.*;

/** Ingredient selection is flattened to primitive foods, preserving the Obsidian table format. */
public final class RecipeIngredients {
  private RecipeIngredients() {}

  public record Item(String name, double grams) {}

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
      if (food == null || !food.ingredients.isEmpty())
        throw new IllegalArgumentException(
            "Ингредиент не найден среди простых продуктов: " + match.group(1));
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
    if (excludedName == null) return true;
    if (VaultMarkdown.key(food.name).equals(VaultMarkdown.key(excludedName))) return false;
    for (Item item : parse(catalog, food.ingredients))
      if (VaultMarkdown.key(item.name()).equals(VaultMarkdown.key(excludedName))) return false;
    return true;
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
    List<Item> added =
        selected.ingredients.isEmpty()
            ? List.of(new Item(selected.name, weight))
            : parse(catalog, selected.ingredients);
    double factor = selected.ingredients.isEmpty() ? 1 : weight / selected.weight;
    LinkedHashMap<String, Item> merged = new LinkedHashMap<>();
    for (Item item : current) merge(merged, item);
    for (Item item : added) merge(merged, new Item(item.name(), grams(item.grams() * factor)));
    return new ArrayList<>(merged.values());
  }

  private static void merge(Map<String, Item> values, Item item) {
    String key = VaultMarkdown.key(item.name());
    Item before = values.get(key);
    values.put(
        key, new Item(item.name(), grams((before == null ? 0 : before.grams()) + item.grams())));
  }
}
