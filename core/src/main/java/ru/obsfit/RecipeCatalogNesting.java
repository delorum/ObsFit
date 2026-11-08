package ru.obsfit;

import java.util.*;

/** One-time conversion of repeated, strictly smaller ingredient sets to visible nested recipes. */
public final class RecipeCatalogNesting {
  private RecipeCatalogNesting() {}

  private record Candidate(RecipeIngredients.Item item, int size, double distance) {}

  public static String convert(String source) {
    var catalog = VaultMarkdown.catalog(source);
    Map<String, String> changed = new HashMap<>();
    for (var owner : catalog.foods) {
      if (owner.ingredients.isEmpty()) continue;
      var items =
          RecipeIngredients.materialize(
              catalog, RecipeIngredients.parse(catalog, owner.ingredients));
      Map<String, Double> actual = amounts(RecipeIngredients.flatten(catalog, items));
      List<Candidate> candidates = new ArrayList<>();
      for (var child : catalog.foods) {
        if (child == owner
            || child.ingredients.isEmpty()
            || !RecipeIngredients.available(catalog, child, owner.name)) continue;
        var tree =
            RecipeIngredients.materialize(
                    catalog, List.of(new RecipeIngredients.Item(child.name, child.weight)))
                .get(0);
        var required = amounts(RecipeIngredients.flatten(catalog, List.of(tree)));
        if (required.size() >= actual.size() || !actual.keySet().containsAll(required.keySet()))
          continue;
        double observed = required.keySet().stream().mapToDouble(actual::get).sum();
        double expected = required.values().stream().mapToDouble(Double::doubleValue).sum();
        double distance =
            required.entrySet().stream()
                .mapToDouble(
                    e -> Math.abs(e.getValue() / expected - actual.get(e.getKey()) / observed))
                .sum();
        candidates.add(new Candidate(tree, required.size(), distance));
      }
      candidates.sort(
          Comparator.comparingInt(Candidate::size)
              .reversed()
              .thenComparingDouble(Candidate::distance)
              .thenComparing(c -> c.item().name()));
      for (var candidate : candidates)
        items = RecipeNesting.organize(catalog, items, List.of(candidate.item()));
      changed.put(owner.name, RecipeIngredients.toMarkdown(items));
    }
    List<String> lines =
        new ArrayList<>(Arrays.asList(source.replace("\r\n", "\n").split("\n", -1)));
    int start = lines.indexOf(VaultMarkdown.START), end = lines.indexOf(VaultMarkdown.END);
    for (int i = start + 1; i < end; i++) {
      String line = lines.get(i).trim();
      if (!line.startsWith("|")) continue;
      String[] cells =
          line.substring(1, line.endsWith("|") ? line.length() - 1 : line.length())
              .split("\\|", -1);
      if (cells.length != 4) continue;
      String name = cells[0].trim().split("(?i)<br\\s*/?\\s*>")[0];
      if (!changed.containsKey(name)) continue;
      cells[1] = " " + changed.get(name) + " ";
      lines.set(i, "|" + String.join("|", cells) + "|");
    }
    String result = String.join(source.contains("\r\n") ? "\r\n" : "\n", lines);
    var updated = VaultMarkdown.catalog(result);
    for (var original : catalog.foods) {
      var food = updated.get(original.name);
      if (Math.abs(food.weight - original.weight) > 1e-8
          || Math.abs(food.kcal100 - original.kcal100) > 1e-7)
        throw new IllegalStateException("Вложение изменило вес или калорийность: " + original.name);
    }
    return result;
  }

  private static Map<String, Double> amounts(List<RecipeIngredients.Item> items) {
    Map<String, Double> result = new HashMap<>();
    for (var item : items) result.merge(VaultMarkdown.key(item.name()), item.grams(), Double::sum);
    return result;
  }
}
