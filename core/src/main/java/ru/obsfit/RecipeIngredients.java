package ru.obsfit;

import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.*;

/** Recipe trees shared by catalog cells, editable portions, diary blocks and inference. */
public final class RecipeIngredients {
  private RecipeIngredients() {}

  public record Item(String name, double grams, List<Item> children) {
    public Item {
      children = List.copyOf(children);
    }

    public Item(String name, double grams) {
      this(name, grams, List.of());
    }
  }

  public record Nutrition(double weight, double kcal100) {}

  public record Portion(String text, double calories) {}

  public static Nutrition nutrition(VaultMarkdown.Catalog catalog, List<Item> items) {
    double weight = 0, calories = 0;
    for (Item item : items) {
      var food = require(catalog, item.name());
      double amount = grams(item.grams());
      weight += amount;
      calories +=
          amount
              * (item.children().isEmpty()
                  ? food.kcal100
                  : nutrition(catalog, item.children()).kcal100())
              / 100;
    }
    grams(weight);
    if (!Double.isFinite(calories))
      throw new IllegalArgumentException("Некорректная калорийность блюда");
    return new Nutrition(weight, calories * 100 / weight);
  }

  private static VaultMarkdown.Food require(VaultMarkdown.Catalog catalog, String name) {
    var food = catalog.get(name);
    if (food == null) throw new IllegalArgumentException("Неизвестный ингредиент: " + name);
    return food;
  }

  private static final Pattern ROW =
      Pattern.compile(
          "^(.+?)\\s+(\\d+(?:[.,]\\d+)?)\\s*г$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  private static final class Builder {
    String name;
    double grams;
    int indent;
    boolean group;
    List<Builder> children = new ArrayList<>();

    Item item() {
      var nested = children.stream().map(Builder::item).toList();
      if (group && nested.isEmpty())
        throw new IllegalArgumentException("Пустой вложенный состав: " + name);
      return new Item(
          name, nested.isEmpty() ? grams : nested.stream().mapToDouble(Item::grams).sum(), nested);
    }
  }

  public static List<Item> parse(VaultMarkdown.Catalog catalog, String text) {
    List<Builder> roots = new ArrayList<>();
    Deque<Builder> stack = new ArrayDeque<>();
    for (String encoded : text.split("(?i)<br\\s*/?\\s*>|\\R")) {
      String row = encoded.replace("&nbsp;", " ").replace('\u00a0', ' ');
      if (row.isBlank()) continue;
      Builder node = new Builder();
      node.indent = row.length() - row.stripLeading().length();
      String body = row.strip().replaceFirst("^[-+*]\\s+", "");
      Matcher match = ROW.matcher(body);
      if (body.endsWith(":")) {
        node.group = true;
        node.name = require(catalog, body.substring(0, body.length() - 1).trim()).name;
      } else if (match.matches()) {
        node.name = require(catalog, match.group(1)).name;
        node.grams = grams(VaultMarkdown.number(match.group(2)));
      } else throw new IllegalArgumentException("Некорректный ингредиент: " + body);
      while (!stack.isEmpty() && stack.peek().indent >= node.indent) stack.pop();
      if (stack.isEmpty()) {
        if (node.indent != 0)
          throw new IllegalArgumentException("Ингредиент без родительского блюда: " + body);
        roots.add(node);
      } else {
        if (!stack.peek().group)
          throw new IllegalArgumentException("Отступ допустим только внутри составного блюда");
        stack.peek().children.add(node);
      }
      stack.push(node);
    }
    List<Item> result = new ArrayList<>();
    for (var root : roots) result.add(root.item());
    return result;
  }

  public static double grams(double value) {
    if (!Double.isFinite(value) || value <= 0 || value > 1_000_000_000)
      throw new IllegalArgumentException("Вес должен быть больше нуля");
    return value;
  }

  public static String toMarkdown(List<Item> items) {
    List<String> rows = new ArrayList<>();
    markdown(items, 0, rows);
    return String.join("<br>", rows);
  }

  private static void markdown(List<Item> items, int depth, List<String> rows) {
    for (var item : items) {
      String prefix = "&nbsp;".repeat(depth * 2) + (depth == 0 ? "" : "- ");
      rows.add(
          prefix
              + item.name()
              + (item.children().isEmpty() ? " " + Numbers.plain(item.grams()) + " г" : ":"));
      markdown(item.children(), depth + 1, rows);
    }
  }

  public static List<Item> materialize(VaultMarkdown.Catalog catalog, List<Item> items) {
    return materialize(catalog, items, new HashSet<>());
  }

  private static List<Item> materialize(
      VaultMarkdown.Catalog catalog, List<Item> items, Set<String> visiting) {
    List<Item> result = new ArrayList<>();
    for (var item : items) {
      var food = require(catalog, item.name());
      grams(item.grams());
      if (food.ingredients.isEmpty() && item.children().isEmpty()) {
        result.add(new Item(food.name, item.grams()));
        continue;
      }
      String key = VaultMarkdown.key(food.name);
      if (!visiting.add(key))
        throw new IllegalArgumentException("Циклический состав: " + food.name);
      var children = item.children().isEmpty() ? parse(catalog, food.ingredients) : item.children();
      children = materialize(catalog, children, visiting);
      double baseWeight = children.stream().mapToDouble(Item::grams).sum();
      result.add(
          new Item(food.name, item.grams(), scale(children, item.grams() / grams(baseWeight))));
      visiting.remove(key);
    }
    return result;
  }

  public static List<Item> scale(List<Item> items, double factor) {
    List<Item> result = new ArrayList<>();
    for (var item : items)
      result.add(
          new Item(item.name(), grams(item.grams() * factor), scale(item.children(), factor)));
    return result;
  }

  public static List<Item> flatten(VaultMarkdown.Catalog catalog, List<Item> items) {
    LinkedHashMap<String, Item> result = new LinkedHashMap<>();
    collect(materialize(catalog, items), result);
    return new ArrayList<>(result.values());
  }

  private static void collect(List<Item> items, Map<String, Item> result) {
    for (var item : items) {
      if (item.children().isEmpty()) merge(result, item);
      else collect(item.children(), result);
    }
  }

  /** Leaf-only mapping preserves all recipe identities and recomputes enclosing weights. */
  public static List<Item> mapLeaves(List<Item> items, UnaryOperator<Item> transform) {
    List<Item> result = new ArrayList<>();
    for (var item : items) {
      if (item.children().isEmpty()) result.add(transform.apply(item));
      else {
        var children = mapLeaves(item.children(), transform);
        result.add(
            new Item(item.name(), children.stream().mapToDouble(Item::grams).sum(), children));
      }
    }
    return result;
  }

  public static List<Item> portionItems(
      VaultMarkdown.Catalog catalog, List<Item> items, double weight) {
    grams(weight);
    var tree = materialize(catalog, items);
    double sum = tree.stream().mapToDouble(Item::grams).sum();
    tree =
        mapLeaves(
            scale(tree, weight / grams(sum)),
            leaf -> {
              double rounded = Math.round(leaf.grams() * 100) / 100d;
              return new Item(leaf.name(), rounded > 0 ? rounded : leaf.grams());
            });
    // Balance rounding on the largest leaf so all nested portions conserve the requested weight.
    List<Item> leaves = new ArrayList<>();
    mapLeaves(
        tree,
        leaf -> {
          leaves.add(leaf);
          return leaf;
        });
    Item largest = leaves.stream().max(Comparator.comparingDouble(Item::grams)).orElseThrow();
    double difference = weight - leaves.stream().mapToDouble(Item::grams).sum();
    if (Math.abs(difference) > 1e-12 && largest.grams() + difference > 0) {
      double balanced = largest.grams() + difference;
      if (balanced >= .01) balanced = Math.round(balanced * 100) / 100d;
      final double amount = grams(balanced);
      tree = mapLeaves(tree, leaf -> leaf == largest ? new Item(leaf.name(), amount) : leaf);
    }
    return tree;
  }

  public static Portion portion(VaultMarkdown.Catalog catalog, List<Item> items, double weight) {
    return render(catalog, portionItems(catalog, items, weight));
  }

  private static Portion render(VaultMarkdown.Catalog catalog, List<Item> items) {
    List<String> lines = new ArrayList<>();
    double total = 0;
    for (var item : items) {
      if (item.children().isEmpty()) {
        var food = require(catalog, item.name());
        lines.add(VaultMarkdown.portion(food.name, item.grams(), food.kcal100));
        total += Math.round(item.grams() * food.kcal100 / 100);
      } else {
        var children = render(catalog, item.children());
        lines.add(RecipeBlocks.wrap(item.name(), children.text()));
        total += children.calories();
      }
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
    return references(catalog, parse(catalog, food.ingredients), target, visited);
  }

  private static boolean references(
      VaultMarkdown.Catalog catalog, List<Item> items, String target, Set<String> visited) {
    for (var item : items)
      if (dependsOn(catalog, require(catalog, item.name()), target, visited)
          || references(catalog, item.children(), target, visited)) return true;
    return false;
  }

  public static List<Item> add(
      VaultMarkdown.Catalog catalog,
      List<Item> current,
      String selectedName,
      double weight,
      String excludedName) {
    var selected = require(catalog, selectedName);
    if (!available(catalog, selected, excludedName))
      throw new IllegalArgumentException("Продукт не может входить в собственный состав");
    List<Item> items = new ArrayList<>(current);
    var addition = materialize(catalog, List.of(new Item(selected.name, grams(weight)))).get(0);
    // Merge only equal siblings. Never merge leaves from different nested dishes.
    for (int i = 0; i < items.size(); i++) {
      var before = items.get(i);
      if (VaultMarkdown.key(before.name()).equals(VaultMarkdown.key(addition.name()))
          && before.children().isEmpty()
          && addition.children().isEmpty()) {
        items.set(i, new Item(before.name(), grams(before.grams() + addition.grams())));
        return items;
      }
    }
    items.add(addition);
    return items;
  }

  private static void merge(Map<String, Item> result, Item item) {
    String key = VaultMarkdown.key(item.name());
    var before = result.get(key);
    result.put(
        key, new Item(item.name(), grams((before == null ? 0 : before.grams()) + item.grams())));
  }

  public static List<Item> rename(List<Item> items, String oldName, String newName) {
    List<Item> result = new ArrayList<>();
    for (var item : items)
      result.add(
          new Item(
              VaultMarkdown.key(item.name()).equals(VaultMarkdown.key(oldName))
                  ? newName
                  : item.name(),
              item.grams(),
              rename(item.children(), oldName, newName)));
    return result;
  }

  public static boolean contains(List<Item> items, String name) {
    return items.stream()
        .anyMatch(
            item ->
                VaultMarkdown.key(item.name()).equals(VaultMarkdown.key(name))
                    || contains(item.children(), name));
  }
}
