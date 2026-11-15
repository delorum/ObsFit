package ru.obsfit;

import java.util.*;

/** Converts an actual diary meal into a catalog draft; never writes either source. */
public final class MealCatalog {
  private MealCatalog() {}

  private static List<Object> children(VaultMarkdown.Day day, String section, int parentLine) {
    Map<Integer, Object> result = new TreeMap<>();
    for (var group : day.recipes)
      if (group.section().equals(section)
          && group.parentLine() == parentLine
          && !group.entries().isEmpty()) result.put(group.line(), group);
    for (var entry : day.section(section)) {
      var parent = RecipeBlocks.parent(day, entry.line);
      if ((parent == null ? -1 : parent.line()) == parentLine) result.put(entry.line, entry);
    }
    return new ArrayList<>(result.values());
  }

  public static int count(VaultMarkdown.Day day, String section) {
    return children(day, VaultMarkdown.sectionName(section), -1).size();
  }

  public static CatalogForm create(
      VaultRepository.Snapshot snapshot, VaultRepository.CatalogSnapshot catalog, String section) {
    section = VaultMarkdown.sectionName(section);
    if (!VaultMarkdown.SECTIONS.contains(section) || section.equals("Заметки"))
      throw new IllegalArgumentException("Выберите приём пищи");
    var day = VaultMarkdown.day(snapshot.day.source, catalog.catalog);
    if (count(day, section) <= 1)
      throw new IllegalStateException("Для сохранения нужны хотя бы две записи в приёме пищи");
    var form = new CatalogForm(catalog, null);
    form.ingredients = collect(day, section, -1, catalog.catalog, form);
    if (!form.ingredients.isEmpty()) {
      form.ingredients = RecipeIngredients.materialize(catalog.catalog, form.ingredients);
      var nutrition = RecipeIngredients.nutrition(catalog.catalog, form.ingredients);
      form.weight = Numbers.plain(nutrition.weight());
      form.kcal100 = VaultMarkdown.fmt(nutrition.kcal100());
    }
    form.ingredientsExpanded = true;
    form.dirty = true;
    return form;
  }

  private static List<RecipeIngredients.Item> collect(
      VaultMarkdown.Day day,
      String section,
      int parentLine,
      VaultMarkdown.Catalog catalog,
      CatalogForm form) {
    List<RecipeIngredients.Item> result = new ArrayList<>();
    for (var node : children(day, section, parentLine)) {
      if (node instanceof RecipeBlocks.Group group) {
        var nested = collect(day, section, group.line(), catalog, form);
        if (group.food() == null) {
          form.importNotes.add(
              "Группа «"
                  + group.name()
                  + "» отсутствует в справочнике — перенесено её содержимое.");
          result.addAll(nested);
        } else if (!nested.isEmpty()) {
          result.add(
              new RecipeIngredients.Item(
                  group.food().name,
                  nested.stream().mapToDouble(RecipeIngredients.Item::grams).sum(),
                  nested));
        }
      } else {
        var entry = (VaultMarkdown.Entry) node;
        var food = catalog.get(entry.name);
        String issue =
            food == null
                ? "нет в справочнике"
                : entry.weight == null || entry.weight <= 0 ? "не указан положительный вес" : null;
        if (issue != null) form.unresolved.add(entry.raw.trim() + " — " + issue);
        else result.add(new RecipeIngredients.Item(food.name, entry.weight));
      }
    }
    return List.copyOf(result);
  }
}
