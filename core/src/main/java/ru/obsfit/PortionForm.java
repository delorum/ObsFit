package ru.obsfit;

import java.io.Serializable;
import java.util.*;

/** Editable values, independent of widgets and the Activity lifecycle. */
public final class PortionForm {
  public final VaultRepository.Snapshot original;
  public final String section;
  public final VaultMarkdown.Entry entry;
  public VaultMarkdown.Food selectedFood;
  public final CatalogForm composition;
  public boolean portionWeightEdited;
  public boolean newFood, manual, dirty, choosing, aliasesExpanded;
  public String name = "",
      aliases = "",
      weight = "100",
      defaultWeight = "100",
      kcal100 = "",
      manualKcal = "",
      query = "";

  public record Draft(
      String section,
      String selectedFood,
      String entryRaw,
      int entryLine,
      boolean newFood,
      boolean manual,
      boolean dirty,
      boolean choosing,
      boolean aliasesExpanded,
      String name,
      String aliases,
      String weight,
      String defaultWeight,
      String kcal100,
      String manualKcal,
      String query,
      String ingredients,
      boolean ingredientsExpanded,
      boolean portionWeightEdited)
      implements Serializable {}

  public record Save(String noteText, VaultMarkdown.Food newFood, String recentName) {}

  public PortionForm(VaultRepository.Snapshot original, String section, VaultMarkdown.Entry entry) {
    this.original = original;
    composition =
        new CatalogForm(
            new VaultRepository.CatalogSnapshot(original.foodText, original.replacementsText),
            null);
    this.section = VaultMarkdown.sectionName(section);
    this.entry = entry;
    this.choosing = entry == null;
    if (entry != null) {
      selectedFood = entry.food;
      manual = entry.weight == null;
      name = entry.name;
      weight = entry.weight == null ? "100" : RecipeIngredients.weightText(entry.weight);
      manualKcal = VaultMarkdown.fmt(entry.kcal);
      if (selectedFood != null) {
        name = selectedFood.name;
        aliases = String.join("\n", selectedFood.aliases);
        kcal100 = VaultMarkdown.fmt(selectedFood.kcal100);
      } else if (entry.weight != null)
        kcal100 = VaultMarkdown.fmt(entry.weight > 0 ? entry.kcal * 100 / entry.weight : 0);
    }
  }

  public void choose(VaultMarkdown.Food food) {
    composition.ingredients.clear();
    composition.ingredientsExpanded = false;
    portionWeightEdited = false;
    selectedFood = food;
    newFood = false;
    manual = false;
    choosing = false;
    name = food.name;
    aliases = String.join("\n", food.aliases);
    kcal100 = VaultMarkdown.fmt(food.kcal100);
    weight =
        entry != null && entry.weight != null
            ? RecipeIngredients.weightText(entry.weight)
            : RecipeIngredients.weightText(food.weight);
  }

  public void startNew() {
    composition.ingredients.clear();
    composition.ingredientsExpanded = false;
    portionWeightEdited = false;
    selectedFood = null;
    newFood = true;
    manual = false;
    choosing = false;
    name = "";
    aliases = "";
    weight =
        entry != null && entry.weight != null ? RecipeIngredients.weightText(entry.weight) : "100";
    defaultWeight = "100";
    kcal100 = "";
    dirty = false;
  }

  public void startManual() {
    composition.ingredients.clear();
    composition.ingredientsExpanded = false;
    portionWeightEdited = false;
    selectedFood = null;
    newFood = false;
    manual = true;
    choosing = false;
    aliasesExpanded = false;
    name = "";
    aliases = "";
    weight = "";
    defaultWeight = "";
    kcal100 = "";
    manualKcal = "";
    dirty = false;
  }

  public boolean recipe() {
    return newFood && composition.recipe();
  }

  public void recalculateRecipe() {
    if (!recipe()) return;
    var calculated = RecipeIngredients.nutrition(original.catalog, composition.ingredients);
    defaultWeight = RecipeIngredients.weightText(calculated.weight());
    kcal100 = VaultMarkdown.fmt(calculated.kcal100());
    if (!portionWeightEdited) weight = defaultWeight;
  }

  public double total() {
    if (recipe())
      return RecipeIngredients.portion(
              original.catalog, composition.ingredients, VaultMarkdown.number(weight))
          .calories();
    if (!manual && selectedFood != null && !selectedFood.ingredients.isEmpty())
      return RecipeIngredients.portion(
              original.catalog,
              List.of(new RecipeIngredients.Item(selectedFood.name, selectedFood.weight)),
              VaultMarkdown.number(weight))
          .calories();
    return manual
        ? VaultMarkdown.number(manualKcal)
        : Math.round(VaultMarkdown.number(weight) * VaultMarkdown.number(kcal100) / 100);
  }

  public void barcode(String productName, Double calories) {
    if (!newFood) return;
    if (!productName.isBlank()) name = productName;
    if (calories != null && !recipe()) kcal100 = VaultMarkdown.fmt(calories);
    dirty = true;
  }

  public Save save() {
    if (entry == null && !manual && !newFood && selectedFood == null)
      throw new IllegalArgumentException("Сначала выберите еду");
    recalculateRecipe();
    String title = name.trim(), line;
    VaultMarkdown.Food added = null;
    VaultMarkdown.Catalog catalog = original.catalog;
    if (manual) {
      if (title.contains("\n")
          || title.contains("\r")
          || title.contains("::")
          || title.contains("`")
          || title.contains("~~")
          || title.matches("^[>#|].*")) throw new IllegalArgumentException("Недопустимое название");
      line =
          (title.isBlank() ? "" : title + " ")
              + VaultMarkdown.fmt(VaultMarkdown.number(manualKcal))
              + " ккал";
      // Descriptions must not turn into weighted portions, comments or Markdown when reopened.
      var parsed = VaultMarkdown.day(line, catalog).entries;
      if (parsed.size() != 1 || parsed.get(0).weight != null || !parsed.get(0).name.equals(title))
        throw new IllegalArgumentException(
            "Описание должно быть одной строкой без Markdown-разметки и веса в конце");
    } else {
      if (newFood) {
        added =
            new VaultMarkdown.Food(
                title,
                aliasLines(aliases),
                RecipeIngredients.toMarkdown(
                    RecipeIngredients.flatten(original.catalog, composition.ingredients)),
                VaultMarkdown.number(kcal100),
                VaultMarkdown.number(defaultWeight));
        catalog =
            VaultMarkdown.catalog(VaultMarkdown.addFood(catalog, added, original.replacementsText));
      }
      VaultMarkdown.Food food = catalog.get(title);
      if (food != null && !food.ingredients.isEmpty()) {
        line =
            RecipeIngredients.portion(
                    catalog,
                    List.of(new RecipeIngredients.Item(food.name, food.weight)),
                    VaultMarkdown.number(weight))
                .text();
      } else {
        line =
            VaultMarkdown.portion(
                title,
                VaultMarkdown.number(weight),
                newFood ? catalog.get(title).kcal100 : VaultMarkdown.number(kcal100));
      }
    }
    String updated =
        entry == null
            ? VaultMarkdown.addAll(
                original.day.source, section, Arrays.asList(line.split("\n", -1)), catalog)
            : VaultMarkdown.edit(original.day.source, entry, line, catalog);
    return new Save(updated, added, title);
  }

  public Draft draft() {
    return new Draft(
        section,
        selectedFood == null ? null : selectedFood.name,
        entry == null ? null : entry.raw,
        entry == null ? -1 : entry.line,
        newFood,
        manual,
        dirty,
        choosing,
        aliasesExpanded,
        name,
        aliases,
        weight,
        defaultWeight,
        kcal100,
        manualKcal,
        query,
        RecipeIngredients.toMarkdown(composition.ingredients),
        composition.ingredientsExpanded,
        portionWeightEdited);
  }

  public static PortionForm restore(VaultRepository.Snapshot snapshot, Draft draft) {
    VaultMarkdown.Entry entry = null;
    if (draft.entryRaw() != null) {
      for (var item : snapshot.day.entries)
        if (item.line == draft.entryLine() && item.raw.equals(draft.entryRaw())) {
          entry = item;
          break;
        }
      if (entry == null)
        throw new IllegalStateException("Редактируемая запись изменилась. Откройте её заново.");
    }
    PortionForm form = new PortionForm(snapshot, draft.section(), entry);
    form.newFood = draft.newFood();
    form.manual = draft.manual();
    form.choosing = draft.choosing();
    form.aliasesExpanded = draft.aliasesExpanded();
    form.query = draft.query();
    if (draft.selectedFood() != null) {
      VaultMarkdown.Food food = snapshot.catalog.get(draft.selectedFood());
      if (food == null)
        throw new IllegalStateException("Еда исчезла из справочника. Откройте запись заново.");
      form.choose(food);
      form.choosing = draft.choosing();
    } else {
      form.selectedFood = null;
      form.name = draft.name();
      form.aliases = draft.aliases();
      form.kcal100 = draft.kcal100();
    }
    form.weight = draft.weight();
    form.defaultWeight = draft.defaultWeight();
    form.manualKcal = draft.manualKcal();
    form.dirty = draft.dirty();
    try {
      form.composition.ingredients =
          RecipeIngredients.parse(
              snapshot.catalog, draft.ingredients() == null ? "" : draft.ingredients());
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException(
          "Состав блюда изменился в справочнике. Откройте форму заново.", error);
    }
    form.composition.ingredientsExpanded = draft.ingredientsExpanded();
    form.portionWeightEdited = draft.portionWeightEdited();
    form.recalculateRecipe();
    return form;
  }

  public static List<String> aliasLines(String text) {
    List<String> names = new ArrayList<>();
    for (String line : text.split("\\R")) if (!line.isBlank()) names.add(line.trim());
    return names;
  }
}
