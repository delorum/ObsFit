package ru.obsfit;

import java.util.*;

/** Direct diary portions plus one inferred recipe per actual meal containing all its leaves. */
public final class FoodOccurrences {
  public record Occurrence(
      VaultMarkdown.Food food,
      String section,
      int line,
      double grams,
      double calories,
      boolean estimated) {}

  private record Leaf(String key, double weightShare, double energyShare) {}

  private final Map<VaultMarkdown.Food, List<Leaf>> leaves = new IdentityHashMap<>();
  private final List<VaultMarkdown.Food> recipes;

  public FoodOccurrences(VaultMarkdown.Catalog catalog) {
    this(catalog, catalog.foods);
  }

  /** History only needs to infer the requested recipe; expansion plans are cached for the scan. */
  public FoodOccurrences(VaultMarkdown.Catalog catalog, List<VaultMarkdown.Food> candidates) {
    recipes = candidates.stream().filter(food -> !food.ingredients.isEmpty()).toList();
    if (recipes.isEmpty()) return;
    for (var food : catalog.foods) {
      var items =
          RecipeIngredients.flatten(
              catalog, List.of(new RecipeIngredients.Item(food.name, food.weight)));
      var nutrition = RecipeIngredients.nutrition(catalog, items);
      double energy = nutrition.weight() * nutrition.kcal100() / 100;
      List<Leaf> plan = new ArrayList<>();
      for (var item : items) {
        double weightShare = item.grams() / nutrition.weight();
        double energyShare =
            energy > 0
                ? item.grams() * catalog.get(item.name()).kcal100 / 100 / energy
                : weightShare;
        plan.add(new Leaf(VaultMarkdown.key(item.name()), weightShare, energyShare));
      }
      leaves.put(food, List.copyOf(plan));
    }
  }

  private static final class Meal {
    final Set<VaultMarkdown.Food> explicit = Collections.newSetFromMap(new IdentityHashMap<>());
    final Map<String, Totals> ingredients = new HashMap<>();
  }

  private static final class Totals {
    double grams, calories;
    int line = -1;

    void add(double amount, double energy, int sourceLine) {
      grams += amount;
      calories += energy;
      line = Math.max(line, sourceLine);
    }
  }

  public List<Occurrence> find(List<VaultMarkdown.Entry> entries) {
    List<Occurrence> result = new ArrayList<>();
    Map<String, Meal> meals = new LinkedHashMap<>();
    for (var entry : entries) {
      if (entry.food == null || entry.weight == null) continue;
      double calories = entry.recordedKcal == null ? entry.kcal : entry.recordedKcal;
      String section = VaultMarkdown.sectionName(entry.section);
      result.add(
          new Occurrence(
              entry.food, section, entry.line, entry.weight, calories, entry.recordedKcal == null));
      if (recipes.isEmpty()) continue;
      Meal meal = meals.computeIfAbsent(section, ignored -> new Meal());
      meal.explicit.add(entry.food);
      if (entry.weight <= 0) continue;
      var plan = leaves.get(entry.food);
      if (plan == null) continue;
      for (var leaf : plan) {
        // Preserve the actual recorded portion energy, even after the catalog has been edited.
        meal.ingredients
            .computeIfAbsent(leaf.key(), ignored -> new Totals())
            .add(entry.weight * leaf.weightShare(), calories * leaf.energyShare(), entry.line);
      }
    }
    for (var section : meals.entrySet()) {
      var meal = section.getValue();
      for (var recipe : recipes) {
        // An explicit recipe row already records this meal; don't also infer a duplicate.
        if (meal.explicit.contains(recipe)) continue;
        var plan = leaves.get(recipe);
        double grams = 0, calories = 0;
        int line = -1;
        boolean complete = true;
        for (var ingredient : plan) {
          var found = meal.ingredients.get(ingredient.key());
          if (found == null) {
            complete = false;
            break;
          }
          grams += found.grams;
          calories += found.calories;
          line = Math.max(line, found.line);
        }
        if (complete)
          result.add(new Occurrence(recipe, section.getKey(), line, grams, calories, true));
      }
    }
    return result;
  }
}
