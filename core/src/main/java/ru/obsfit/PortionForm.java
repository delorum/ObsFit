package ru.obsfit;

import java.io.Serializable;
import java.util.*;

/** Editable values, independent of widgets and the Activity lifecycle. */
public final class PortionForm {
  public final VaultRepository.Snapshot original;
  public final String section;
  public final VaultMarkdown.Entry entry;
  public VaultMarkdown.Food selectedFood;
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
      String query)
      implements Serializable {}

  public record Save(String noteText, VaultMarkdown.Food newFood, String recentName) {}

  public PortionForm(VaultRepository.Snapshot original, String section, VaultMarkdown.Entry entry) {
    this.original = original;
    this.section = section;
    this.entry = entry;
    this.choosing = entry == null;
    if (entry != null) {
      selectedFood = entry.food;
      manual = entry.weight == null;
      name = entry.name;
      weight = entry.weight == null ? "100" : VaultMarkdown.fmt(entry.weight);
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
    selectedFood = food;
    newFood = false;
    manual = false;
    choosing = false;
    name = food.name;
    aliases = String.join("\n", food.aliases);
    kcal100 = VaultMarkdown.fmt(food.kcal100);
    weight =
        entry != null && entry.weight != null
            ? VaultMarkdown.fmt(entry.weight)
            : VaultMarkdown.fmt(food.weight);
  }

  public void startNew() {
    selectedFood = null;
    newFood = true;
    manual = false;
    choosing = false;
    name = "";
    aliases = "";
    weight = entry != null && entry.weight != null ? VaultMarkdown.fmt(entry.weight) : "100";
    defaultWeight = "100";
    kcal100 = "";
    dirty = false;
  }

  public void startManual() {
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

  public double total() {
    return manual
        ? VaultMarkdown.number(manualKcal)
        : Math.round(VaultMarkdown.number(weight) * VaultMarkdown.number(kcal100) / 100);
  }

  public void barcode(String productName, Double calories) {
    if (!newFood) return;
    if (!productName.isBlank()) name = productName;
    if (calories != null) kcal100 = VaultMarkdown.fmt(calories);
    dirty = true;
  }

  public Save save() {
    if (entry == null && !manual && !newFood && selectedFood == null)
      throw new IllegalArgumentException("Сначала выберите еду");
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
      line =
          VaultMarkdown.portion(title, VaultMarkdown.number(weight), VaultMarkdown.number(kcal100));
      if (newFood) {
        added =
            new VaultMarkdown.Food(
                title,
                aliasLines(aliases),
                "",
                VaultMarkdown.number(kcal100),
                VaultMarkdown.number(defaultWeight));
        catalog =
            VaultMarkdown.catalog(VaultMarkdown.addFood(catalog, added, original.replacementsText));
      }
    }
    String updated =
        entry == null
            ? VaultMarkdown.add(original.day.source, section, line, catalog)
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
        query);
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
    return form;
  }

  public static List<String> aliasLines(String text) {
    List<String> names = new ArrayList<>();
    for (String line : text.split("\\R")) if (!line.isBlank()) names.add(line.trim());
    return names;
  }
}
