package ru.obsfit;

import java.util.*;
import java.util.regex.*;

/** Shared complete workout plans in Obsidian, separate from the daily recorded approaches. */
public final class SportsPlan {
  public static final String PATH = "Дневник/Спорт и диета/История спорта.md";

  public static String key(SportsDiary.Exercise exercise) {
    return switch (exercise.key()) {
      case "Отжимания" -> "ПланОтжимания";
      case "ГантелиБицепс" -> "ПланГантелиБицепс";
      case "ГантелиТрицепсВверх" -> "ПланГантелиВверх";
      case "Пресс" -> "ПланПресс";
      case "Приседания" -> "ПланПриседания";
      case "Велотренажер" -> "ПланВелотренажер";
      default -> throw new IllegalArgumentException("Неизвестное упражнение");
    };
  }

  public final String source;
  private final Map<String, Integer> indices = new HashMap<>();
  private final Map<String, List<SportsDiary.Set>> plans = new HashMap<>();
  private final List<String> lines;

  public SportsPlan(String source) {
    this.source = source;
    lines = Arrays.asList(source.replace("\r\n", "\n").split("\n", -1));
    boolean[] text = VaultMarkdown.textLines(lines.toArray(String[]::new));
    for (var e : SportsDiary.EXERCISES) {
      String key = key(e);
      Pattern field = Pattern.compile("^[ \\t]*" + Pattern.quote(key) + "::[ \\t]*(.*)$");
      for (int i = 0; i < lines.size(); i++) {
        if (!text[i]) continue;
        var match = field.matcher(lines.get(i));
        if (!match.matches()) continue;
        if (indices.putIfAbsent(key, i) != null)
          throw new IllegalArgumentException("Повторяющийся ключ: " + key);
        plans.put(key, validate(e, match.group(1)));
      }
      if (!indices.containsKey(key))
        throw new IllegalArgumentException("В История спорта.md отсутствует " + key + "::");
    }
  }

  public static List<SportsDiary.Set> validate(SportsDiary.Exercise e, String value) {
    var sets = SportsDiary.parse(e, value);
    if (sets.isEmpty()
        || sets.stream().mapToDouble(SportsDiary.Set::first).sum() <= 0
        || e.paired() && sets.stream().mapToDouble(s -> s.second()).sum() <= 0)
      throw new IllegalArgumentException(key(e) + ": задайте положительный план");
    return sets;
  }

  public static String format(SportsDiary.Exercise e, List<SportsDiary.Set> sets) {
    return String.join("|", sets.stream().map(s -> s.format(e)).toList());
  }

  public String value(SportsDiary.Exercise e) {
    return format(e, plans.get(key(e)));
  }

  public SportsDiary.Set approach(SportsDiary.Exercise e, int index) {
    if (index < 0) throw new IllegalArgumentException("Некорректный номер подхода");
    var sets = plans.get(key(e));
    return sets.get(Math.min(index, sets.size() - 1));
  }

  public String update(SportsDiary.Exercise e, String raw) {
    String value = format(e, validate(e, raw));
    List<String> changed = new ArrayList<>(lines);
    int i = indices.get(key(e));
    String line = lines.get(i);
    changed.set(i, line.substring(0, line.indexOf("::") + 2) + " " + value);
    return String.join(source.contains("\r\n") ? "\r\n" : "\n", changed);
  }
}
