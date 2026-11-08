package ru.obsfit;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class CatalogForm {
  public final VaultRepository.CatalogSnapshot original;
  public final VaultMarkdown.Food food;
  public String name, aliases, weight, kcal100;
  public List<RecipeIngredients.Item> ingredients;
  public boolean dirty, ingredientsExpanded;

  public record Draft(
      String originalName,
      String sourceDigest,
      String name,
      String aliases,
      String weight,
      String kcal100,
      String ingredients,
      boolean expanded,
      boolean dirty)
      implements Serializable {}

  public record Preview(Double portionCalories, String error) {}

  public CatalogForm(VaultRepository.CatalogSnapshot original, VaultMarkdown.Food food) {
    this.original = original;
    this.food = food;
    name = food == null ? "" : food.name;
    aliases = food == null ? "" : String.join("\n", food.aliases);
    weight = food == null ? "100" : VaultMarkdown.fmt(food.weight);
    kcal100 = food == null ? "" : VaultMarkdown.fmt(food.kcal100);
    ingredients =
        RecipeIngredients.materialize(
            original.catalog,
            RecipeIngredients.parse(original.catalog, food == null ? "" : food.ingredients));
  }

  public boolean recipe() {
    return !ingredients.isEmpty();
  }

  public VaultMarkdown.Food input() {
    return new VaultMarkdown.Food(
        name,
        PortionForm.aliasLines(aliases),
        RecipeIngredients.toMarkdown(RecipeIngredients.materialize(original.catalog, ingredients)),
        recipe() ? 0 : VaultMarkdown.number(kcal100),
        recipe() ? 0 : VaultMarkdown.number(weight));
  }

  public String save() {
    return VaultMarkdown.putFood(
        original.catalog, food == null ? null : food.name, input(), original.replacementsText);
  }

  public Preview preview() {
    if (name.isBlank() || !recipe() && kcal100.isBlank())
      return new Preview(null, "Заполните название и калорийность продукта");
    try {
      VaultMarkdown.Food calculated = VaultMarkdown.catalog(save()).get(name);
      if (recipe()) {
        weight = VaultMarkdown.fmt(calculated.weight);
        kcal100 = VaultMarkdown.fmt(calculated.kcal100);
      }
      return new Preview((double) Math.round(calculated.weight * calculated.kcal100 / 100), null);
    } catch (IllegalArgumentException error) {
      return new Preview(null, error.getMessage());
    }
  }

  public void add(VaultMarkdown.Food selected, double grams) {
    ingredients =
        RecipeIngredients.add(
            original.catalog, ingredients, selected.name, grams, food == null ? null : food.name);
    dirty = true;
    ingredientsExpanded = true;
  }

  public record Node(int index, int parent, int depth, RecipeIngredients.Item item) {}

  public List<Node> nodes() {
    List<Node> result = new ArrayList<>();
    int[] next = {ingredients.size()};
    for (int i = 0; i < ingredients.size(); i++) {
      result.add(new Node(i, -1, 0, ingredients.get(i)));
      nodes(ingredients.get(i).children(), i, 1, next, result);
    }
    return result;
  }

  private static void nodes(
      List<RecipeIngredients.Item> items, int parent, int depth, int[] next, List<Node> result) {
    for (var item : items) {
      int index = next[0]++;
      result.add(new Node(index, parent, depth, item));
      nodes(item.children(), index, depth + 1, next, result);
    }
  }

  public RecipeIngredients.Item item(int index) {
    return nodes().stream()
        .filter(node -> node.index() == index)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Ингредиент больше не найден"))
        .item();
  }

  private static List<RecipeIngredients.Item> replace(
      List<RecipeIngredients.Item> items,
      RecipeIngredients.Item target,
      RecipeIngredients.Item replacement) {
    List<RecipeIngredients.Item> result = new ArrayList<>();
    for (var item : items) {
      if (item == target) {
        if (replacement != null) result.add(replacement);
      } else if (!item.children().isEmpty()) {
        var children = replace(item.children(), target, replacement);
        if (!children.isEmpty())
          result.add(
              new RecipeIngredients.Item(
                  item.name(),
                  children.stream().mapToDouble(RecipeIngredients.Item::grams).sum(),
                  children));
      } else result.add(item);
    }
    return result;
  }

  public void changeWeight(int index, double grams) {
    var item = item(index);
    if (!item.children().isEmpty())
      throw new IllegalStateException("Вес вложенного блюда изменяется через ингредиенты");
    double amount = RecipeIngredients.grams(grams);
    ingredients =
        replace(
            ingredients,
            item,
            new RecipeIngredients.Item(
                item.name(),
                amount,
                RecipeIngredients.scale(item.children(), amount / item.grams())));
    dirty = true;
  }

  public void remove(int index) {
    ingredients = replace(ingredients, item(index), null);
    dirty = true;
  }

  public void barcode(String productName, Double calories) {
    if (!productName.isBlank()) name = productName;
    if (calories != null && !recipe()) kcal100 = VaultMarkdown.fmt(calories);
    dirty = true;
  }

  public Draft draft() {
    return new Draft(
        food == null ? null : food.name,
        digest(original.foodText),
        name,
        aliases,
        weight,
        kcal100,
        RecipeIngredients.toMarkdown(RecipeIngredients.materialize(original.catalog, ingredients)),
        ingredientsExpanded,
        dirty);
  }

  public static CatalogForm restore(VaultRepository.CatalogSnapshot source, Draft draft) {
    if (!digest(source.foodText).equals(draft.sourceDigest()))
      throw new IllegalStateException(
          "Справочник изменился после закрытия формы. Откройте продукт заново.");
    VaultMarkdown.Food food =
        draft.originalName() == null ? null : source.catalog.get(draft.originalName());
    if (draft.originalName() != null && food == null)
      throw new IllegalStateException("Продукт больше не найден в справочнике.");
    CatalogForm form = new CatalogForm(source, food);
    form.name = draft.name();
    form.aliases = draft.aliases();
    form.weight = draft.weight();
    form.kcal100 = draft.kcal100();
    form.ingredients =
        RecipeIngredients.materialize(
            source.catalog, RecipeIngredients.parse(source.catalog, draft.ingredients()));
    form.ingredientsExpanded = draft.expanded();
    form.dirty = draft.dirty();
    return form;
  }

  private static String digest(String text) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : bytes)
        out.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
      return out.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
