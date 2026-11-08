package ru.obsfit;

import java.time.LocalDate;
import java.util.*;

/**
 * Restore visible nested labels in already marked portions, without modifying any ingredient data.
 */
public final class RecipeNesting {
  private RecipeNesting() {}

  public static List<RecipeIngredients.Item> organize(
      VaultMarkdown.Catalog catalog,
      List<RecipeIngredients.Item> actual,
      List<RecipeIngredients.Item> template) {
    List<RecipeIngredients.Item> result = new ArrayList<>(actual);
    for (var expected : template) {
      if (expected.children().isEmpty()) continue;
      Map<String, Double> required = new HashMap<>();
      for (var item : RecipeIngredients.flatten(catalog, List.of(expected)))
        required.put(VaultMarkdown.key(item.name()), item.grams());
      double targetWeight = required.values().stream().mapToDouble(Double::doubleValue).sum();
      int bestStart = -1, bestEnd = -1;
      double bestDistance = Double.POSITIVE_INFINITY;
      for (int start = 0; start < result.size(); start++) {
        Map<String, Double> found = new HashMap<>();
        for (int end = start; end < result.size(); end++) {
          var candidate = result.get(end);
          if (!candidate.children().isEmpty()
              || !required.containsKey(VaultMarkdown.key(candidate.name()))) break;
          found.merge(VaultMarkdown.key(candidate.name()), candidate.grams(), Double::sum);
          if (found.size() != required.size()) continue;
          double weight = found.values().stream().mapToDouble(Double::doubleValue).sum();
          double distance =
              required.entrySet().stream()
                  .mapToDouble(
                      e -> Math.abs(e.getValue() / targetWeight - found.get(e.getKey()) / weight))
                  .sum();
          if (distance < bestDistance - 1e-12) {
            bestDistance = distance;
            bestStart = start;
            bestEnd = end + 1;
          }
        }
      }
      if (bestStart >= 0) {
        var children =
            organize(
                catalog, new ArrayList<>(result.subList(bestStart, bestEnd)), expected.children());
        var replacement =
            new RecipeIngredients.Item(
                expected.name(),
                children.stream().mapToDouble(RecipeIngredients.Item::grams).sum(),
                children);
        result.subList(bestStart, bestEnd).clear();
        result.add(bestStart, replacement);
      }
    }
    return result;
  }

  private record Change(int start, int end, List<String> lines) {}

  public static String mark(LocalDate date, String source, VaultMarkdown.Catalog catalog) {
    if (date.isBefore(RecipeMigration.START)) return source;
    var day = VaultMarkdown.day(source, catalog);
    List<String> sourceLines =
        new ArrayList<>(Arrays.asList(source.replace("\r\n", "\n").split("\n", -1)));
    List<Change> changes = new ArrayList<>();
    for (var group : day.recipes) {
      if (group.parentLine() != -1
          || group.food() == null
          || group.entries().isEmpty()
          || day.recipes.stream().anyMatch(other -> other.parentLine() == group.line())
          || group.entries().stream().anyMatch(entry -> entry.food == null || entry.weight == null))
        continue;
      var actual =
          group.entries().stream()
              .map(e -> new RecipeIngredients.Item(e.food.name, e.weight))
              .toList();
      var template =
          RecipeIngredients.materialize(
              catalog, RecipeIngredients.parse(catalog, group.food().ingredients));
      var organized = organize(catalog, actual, template);
      if (organized.equals(actual)) continue;
      Deque<VaultMarkdown.Entry> original = new ArrayDeque<>(group.entries());
      String body = render(organized, original);
      if (!original.isEmpty())
        throw new IllegalStateException("Не все исходные ингредиенты сохранены");
      String header = sourceLines.get(group.line());
      String indent = header.substring(0, header.length() - header.stripLeading().length());
      List<String> rendered = new ArrayList<>();
      String wrapped = RecipeBlocks.wrap(group.name(), body);
      for (String line : wrapped.split("\n")) rendered.add(indent + line);
      changes.add(new Change(group.line(), group.end(), rendered));
    }
    changes.sort(Comparator.comparingInt(Change::start).reversed());
    for (var change : changes) {
      sourceLines.subList(change.start(), change.end()).clear();
      sourceLines.addAll(change.start(), change.lines());
    }
    return String.join(source.contains("\r\n") ? "\r\n" : "\n", sourceLines);
  }

  private static String render(
      List<RecipeIngredients.Item> items, Deque<VaultMarkdown.Entry> entries) {
    List<String> lines = new ArrayList<>();
    for (var item : items) {
      if (item.children().isEmpty()) {
        var entry = entries.removeFirst();
        if (!VaultMarkdown.key(entry.food.name).equals(VaultMarkdown.key(item.name())))
          throw new IllegalStateException("Изменился порядок ингредиентов");
        lines.add(entry.raw.substring(entry.prefix.length()));
      } else lines.add(RecipeBlocks.wrap(item.name(), render(item.children(), entries)));
    }
    return String.join("\n", lines);
  }
}
