package ru.obsfit;

import java.util.List;

/** Shared catalog/chooser labels; portion calories use exactly the diary expansion and rounding. */
public final class FoodLabels {
  private FoodLabels() {}

  public static String title(VaultMarkdown.Food food) {
    return food.name + (food.ingredients.isEmpty() ? "" : " ⊞");
  }

  public static String details(VaultMarkdown.Catalog catalog, VaultMarkdown.Food food) {
    double calories =
        RecipeIngredients.portion(
                catalog, List.of(new RecipeIngredients.Item(food.name, food.weight)), food.weight)
            .calories();
    return Numbers.plain(food.weight)
        + " г · "
        + VaultMarkdown.fmt(food.kcal100)
        + " ккал/100 г · "
        + VaultMarkdown.fmt(calories)
        + " ккал/порц.";
  }
}
