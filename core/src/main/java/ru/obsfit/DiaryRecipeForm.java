package ru.obsfit;

import java.io.Serializable;
import java.util.*;

/** A diary-only draft. Recorded ingredient calories survive unrelated edits unchanged. */
public final class DiaryRecipeForm {
  private static final String HEADER = "## Питание\n### Завтрак\n";
  private static final String LABEL = "Дневниковая порция:";
  public final VaultRepository.Snapshot original;
  public final RecipeBlocks.Group group;
  public String name;
  private String body;
  public boolean dirty;

  public record Draft(
      String daySource, String foodSource, int line, String name, String body, boolean dirty)
      implements Serializable {}

  public DiaryRecipeForm(VaultRepository.Snapshot original, RecipeBlocks.Group group) {
    this.original = original;
    this.group = RecipeBlocks.requireGroup(original.day, group);
    name = group.name();
    String[] lines = group.raw().split("\n", -1);
    int indent = RecipeBlocks.indent(lines[0]);
    List<String> children = new ArrayList<>();
    for (int i = 1; i < lines.length; i++) children.add(lines[i].substring(indent));
    body = String.join("\n", children);
  }

  public VaultMarkdown.Day day() {
    return VaultMarkdown.day(HEADER + LABEL + "\n" + body, original.catalog);
  }

  private void accept(String source) {
    var root =
        VaultMarkdown.day(source, original.catalog).recipes.stream()
            .filter(g -> g.line() == 2)
            .findFirst();
    body = root.map(g -> g.raw().substring(g.raw().indexOf('\n') + 1)).orElse("");
    dirty = true;
  }

  public void remove(int line) {
    var day = day();
    var nested = day.recipes.stream().filter(g -> g.line() == line && line != 2).findFirst();
    if (nested.isPresent()) accept(RecipeBlocks.delete(day.source, nested.get(), original.catalog));
    else {
      var entry = day.entries.stream().filter(e -> e.line == line).findFirst().orElseThrow();
      accept(VaultMarkdown.edit(day.source, entry, null, original.catalog));
    }
  }

  public void weight(int line, String value) {
    var day = day();
    var entry = day.entries.stream().filter(e -> e.line == line).findFirst().orElseThrow();
    if (entry.weight == null) throw new IllegalArgumentException("У записи нет веса");
    double grams = RecipeIngredients.grams(VaultMarkdown.number(value));
    double density = entry.food == null ? entry.kcal * 100 / entry.weight : entry.food.kcal100;
    accept(
        VaultMarkdown.edit(
            day.source,
            entry,
            VaultMarkdown.portion(entry.name, grams, density),
            original.catalog));
  }

  public void add(VaultMarkdown.Food food, String value) {
    if (original.catalog.get(food.name) != food)
      throw new IllegalStateException("Справочник изменился");
    double grams = RecipeIngredients.grams(VaultMarkdown.number(value));
    String addition =
        food.ingredients.isEmpty()
            ? VaultMarkdown.portion(food.name, grams, food.kcal100)
            : RecipeBlocks.wrap(
                food.name,
                RecipeIngredients.portion(
                        original.catalog,
                        RecipeIngredients.parse(original.catalog, food.ingredients),
                        grams)
                    .text());
    String wrapped = RecipeBlocks.wrap("Порция", addition);
    String children = wrapped.substring(wrapped.indexOf('\n') + 1);
    body = body.isEmpty() ? children : body + "\n" + children;
    dirty = true;
  }

  public String save() {
    RecipeBlocks.checkName(name);
    if (day().entries.isEmpty())
      throw new IllegalArgumentException("Добавьте хотя бы один ингредиент");
    return RecipeBlocks.replace(
        original.day.source, group, name.trim() + ":\n" + body, original.catalog);
  }

  public Draft draft() {
    return new Draft(original.day.source, original.foodText, group.line(), name, body, dirty);
  }

  public static DiaryRecipeForm restore(VaultRepository.Snapshot original, Draft draft) {
    if (original == null
        || !original.day.source.equals(draft.daySource())
        || !original.foodText.equals(draft.foodSource()))
      throw new IllegalStateException("Дневник или справочник изменился. Откройте блюдо заново.");
    var group =
        original.day.recipes.stream()
            .filter(g -> g.line() == draft.line())
            .findFirst()
            .orElseThrow();
    var form = new DiaryRecipeForm(original, group);
    form.name = draft.name();
    form.body = draft.body();
    form.dirty = draft.dirty();
    return form;
  }
}
