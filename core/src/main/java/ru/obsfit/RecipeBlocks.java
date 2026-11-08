package ru.obsfit;

import java.util.*;
import java.util.regex.*;

/**
 * Visible recipe labels followed by indented ingredient lists. Labels never contribute calories.
 */
public final class RecipeBlocks {
  private RecipeBlocks() {}

  public record Group(
      int line,
      int end,
      int parentLine,
      String raw,
      String name,
      VaultMarkdown.Food food,
      String section,
      List<VaultMarkdown.Entry> entries) {
    public double grams() {
      return entries.stream().filter(e -> e.weight != null).mapToDouble(e -> e.weight).sum();
    }

    public double calories() {
      return entries.stream().mapToDouble(e -> e.kcal).sum();
    }
  }

  private static final Pattern LABEL =
      Pattern.compile("^\\s*(?:(?:[-+*]|\\d+[.)])\\s+)?(.*?)\\s*:$");

  static String label(String line) {
    Matcher match = LABEL.matcher(line);
    if (!match.matches()) return null;
    String name = match.group(1).trim();
    if (name.isBlank()
        || "#>|`~".indexOf(name.charAt(0)) >= 0
        || name.contains("::")
        || name.endsWith(":")) return null;
    return name;
  }

  private static int indent(String line) {
    int count = 0;
    while (count < line.length() && Character.isWhitespace(line.charAt(count))) count++;
    return count;
  }

  static List<Group> parse(
      String source, List<VaultMarkdown.Entry> entries, VaultMarkdown.Catalog catalog) {
    String[] lines = source.replace("\r\n", "\n").split("\n", -1);
    boolean[] text = VaultMarkdown.textLines(lines);
    List<Group> groups = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      String name = text[i] ? label(lines[i]) : null;
      if (name == null) continue;
      int end = i + 1;
      while (end < lines.length
          && !lines[end].isBlank()
          && text[end]
          && indent(lines[end]) > indent(lines[i])) end++;
      List<VaultMarkdown.Entry> children = new ArrayList<>();
      for (var entry : entries) if (entry.line > i && entry.line < end) children.add(entry);
      var food = catalog.get(name);
      if (children.isEmpty() && (food == null || food.ingredients.isEmpty())) continue;
      int parent = -1;
      for (var previous : groups) if (i > previous.line && i < previous.end) parent = previous.line;
      groups.add(
          new Group(
              i,
              end,
              parent,
              String.join("\n", Arrays.copyOfRange(lines, i, end)),
              food == null ? name : food.name,
              food,
              children.isEmpty() ? "Заметки" : children.get(0).section,
              List.copyOf(children)));
    }
    return List.copyOf(groups);
  }

  public static String wrap(String name, String ingredients) {
    List<String> lines = new ArrayList<>();
    lines.add(name + ":");
    for (String line : ingredients.split("\\R"))
      lines.add((Character.isWhitespace(line.charAt(0)) ? "  " : "  - ") + line);
    return String.join("\n", lines);
  }

  private static Group requireGroup(VaultMarkdown.Day day, Group expected) {
    return day.recipes.stream()
        .filter(
            group ->
                group.line() == expected.line()
                    && group.end() == expected.end()
                    && group.parentLine() == expected.parentLine()
                    && group.section().equals(expected.section())
                    && group.name().equals(expected.name())
                    && group.raw().equals(expected.raw()))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException("Блюдо изменилось. Обновите день и повторите действие."));
  }

  private static String remove(String source, Group group, VaultMarkdown.Catalog catalog) {
    var day = VaultMarkdown.day(source, catalog);
    requireGroup(day, group);
    var ancestors =
        day.recipes.stream()
            .filter(parent -> parent.line() < group.line() && parent.end() >= group.end())
            .toList();
    List<String> lines =
        new ArrayList<>(Arrays.asList(source.replace("\r\n", "\n").split("\n", -1)));
    lines.subList(group.line(), group.end()).clear();
    String removed = String.join(source.contains("\r\n") ? "\r\n" : "\n", lines);
    return cleanAfterEdit(removed, catalog, ancestors);
  }

  /** Whole subtree removal, including all descendant labels, with empty ancestor cleanup. */
  public static String delete(String source, Group group, VaultMarkdown.Catalog catalog) {
    return VaultMarkdown.recalculate(remove(source, group, catalog), catalog);
  }

  /**
   * Promote the subtree to a top-level portion in another meal, preserving relative indentation.
   */
  public static String move(
      String source, Group group, String target, VaultMarkdown.Catalog catalog) {
    target = VaultMarkdown.sectionName(target);
    if (!VaultMarkdown.SECTIONS.contains(target))
      throw new IllegalArgumentException("Неизвестный раздел");
    if (target.equals(group.section())) {
      requireGroup(VaultMarkdown.day(source, catalog), group);
      return source;
    }
    List<String> block = new ArrayList<>(Arrays.asList(group.raw().split("\n", -1)));
    int leading = indent(block.get(0));
    block.set(0, block.get(0).stripLeading().replaceFirst("^(?:[-+*]|\\d+[.)])\\s+", ""));
    for (int i = 1; i < block.size(); i++) block.set(i, block.get(i).substring(leading));
    String removed = remove(source, group, catalog);
    return VaultMarkdown.addAll(removed, target, block, catalog);
  }

  /** Remove only recipe labels whose final ingredient was just removed or moved out. */
  static String cleanAfterEdit(String source, VaultMarkdown.Catalog catalog, List<Group> affected) {
    if (affected.isEmpty()) return source;
    var updated = VaultMarkdown.day(source, catalog);
    Map<Integer, Group> current = new HashMap<>();
    for (var group : updated.recipes) current.put(group.line(), group);
    Set<Integer> empty = new TreeSet<>(Comparator.reverseOrder());
    for (var old : affected) {
      var group = current.get(old.line());
      if (group == null || group.entries().isEmpty()) empty.add(old.line());
    }
    List<String> lines =
        new ArrayList<>(Arrays.asList(source.replace("\r\n", "\n").split("\n", -1)));
    for (int line : empty) lines.remove(line);
    return String.join(source.contains("\r\n") ? "\r\n" : "\n", lines);
  }
}
