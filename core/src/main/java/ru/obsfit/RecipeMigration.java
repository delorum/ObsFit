package ru.obsfit;

import java.time.LocalDate;
import java.util.*;

/** One-time, idempotent backfill. Never merges food across intervening text or meal sections. */
public final class RecipeMigration {
  private RecipeMigration() {}

  public static final LocalDate START = LocalDate.of(2026, 11, 1);

  private record Change(int first, int end, String text, int ingredients, double distance) {}

  public static String mark(LocalDate date, String source, VaultMarkdown.Catalog catalog) {
    if (date.isBefore(START)) return source;
    var day = VaultMarkdown.day(source, catalog);
    String[] lines = source.replace("\r\n", "\n").split("\n", -1);
    Set<Integer> owned = new HashSet<>();
    for (var group : day.recipes) for (var entry : group.entries()) owned.add(entry.line);
    List<Change> candidates = new ArrayList<>(), chosen = new ArrayList<>();
    for (var entry : day.entries) {
      if (owned.contains(entry.line)
          || entry.food == null
          || entry.weight == null
          || entry.weight <= 0) continue;
      if (!entry.food.ingredients.isEmpty()) {
        var items =
            RecipeIngredients.portionItems(
                catalog,
                List.of(new RecipeIngredients.Item(entry.food.name, entry.food.weight)),
                entry.weight);
        double energy =
            items.stream()
                .mapToDouble(item -> catalog.get(item.name()).kcal100 * item.grams())
                .sum();
        double remaining = entry.recordedKcal == null ? entry.kcal : entry.recordedKcal;
        double total = remaining;
        List<String> children = new ArrayList<>();
        double weightSum = items.stream().mapToDouble(RecipeIngredients.Item::grams).sum();
        for (int i = 0; i < items.size(); i++) {
          var item = items.get(i);
          double share =
              energy > 0
                  ? catalog.get(item.name()).kcal100 * item.grams() / energy
                  : item.grams() / weightSum;
          double kcal =
              i == items.size() - 1 ? remaining : Math.min(remaining, Math.round(total * share));
          remaining -= kcal;
          children.add(
              item.name()
                  + " "
                  + Numbers.plain(item.grams())
                  + " г "
                  + VaultMarkdown.fmt(kcal)
                  + " ккал");
        }
        String indent = entry.prefix.replaceFirst("(?:[-+*]|\\d+[.)])\\s+$", "");
        String wrapped = RecipeBlocks.wrap(entry.food.name, String.join("\n", children));
        wrapped =
            Arrays.stream(wrapped.split("\n"))
                .map(line -> indent + line)
                .reduce((a, b) -> a + "\n" + b)
                .orElseThrow();
        chosen.add(new Change(entry.line, entry.line + 1, wrapped, 0, 0));
      }
    }
    for (var food : catalog.foods) {
      if (food.ingredients.isEmpty()) continue;
      var leaves =
          RecipeIngredients.flatten(
              catalog, List.of(new RecipeIngredients.Item(food.name, food.weight)));
      Map<String, Double> required = new HashMap<>();
      for (var leaf : leaves) required.put(VaultMarkdown.key(leaf.name()), leaf.grams());
      double defaultWeight = required.values().stream().mapToDouble(Double::doubleValue).sum();
      for (int start = 0; start < day.entries.size(); start++) {
        Map<String, Double> found = new HashMap<>();
        List<VaultMarkdown.Entry> run = new ArrayList<>();
        for (int at = start; at < day.entries.size(); at++) {
          var entry = day.entries.get(at);
          if (entry.food == null
              || entry.weight == null
              || entry.weight <= 0
              || owned.contains(entry.line)
              || !entry.food.ingredients.isEmpty()
              || !required.containsKey(VaultMarkdown.key(entry.food.name))) break;
          if (!run.isEmpty()) {
            var previous = run.get(run.size() - 1);
            if (!previous.section.equals(entry.section)) break;
            boolean adjacent = true;
            for (int line = previous.line + 1; line < entry.line; line++)
              if (!lines[line].isBlank()) {
                adjacent = false;
                break;
              }
            if (!adjacent) break;
          }
          run.add(entry);
          found.merge(VaultMarkdown.key(entry.food.name), entry.weight, Double::sum);
          if (found.size() != required.size()) continue;
          double weight = found.values().stream().mapToDouble(Double::doubleValue).sum();
          double distance =
              required.entrySet().stream()
                  .mapToDouble(
                      item ->
                          Math.abs(
                              item.getValue() / defaultWeight - found.get(item.getKey()) / weight))
                  .sum();
          String ingredients =
              String.join(
                  "\n",
                  run.stream()
                      .map(item -> item.raw.substring(item.prefix.length()).stripTrailing())
                      .toList());
          candidates.add(
              new Change(
                  run.get(0).line,
                  entry.line + 1,
                  RecipeBlocks.wrap(food.name, ingredients),
                  required.size(),
                  distance));
          break;
        }
      }
    }
    candidates.sort(
        Comparator.comparingInt(Change::ingredients)
            .reversed()
            .thenComparingDouble(Change::distance)
            .thenComparingInt(Change::first));
    for (var candidate : candidates)
      if (chosen.stream()
          .noneMatch(other -> candidate.first < other.end && other.first < candidate.end))
        chosen.add(candidate);
    chosen.sort(Comparator.comparingInt(Change::first).reversed());
    List<String> result = new ArrayList<>(Arrays.asList(lines));
    for (var change : chosen) {
      result.subList(change.first, change.end).clear();
      result.addAll(change.first, Arrays.asList(change.text.split("\n")));
    }
    return String.join(source.contains("\r\n") ? "\r\n" : "\n", result);
  }
}
