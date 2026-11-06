package ru.obsfit;

import java.util.*;
import java.util.regex.*;

/** One setting per exercise, backed by illustrative set notation in Obsidian. */
public final class SportsPlan {
  public static final String PATH = "Дневник/Спорт и диета/История спорта.md";

  public final String source;
  private final Map<String, Integer> indices = new HashMap<>();
  private final Map<String, Double> values = new HashMap<>();
  private final List<String> lines;

  public SportsPlan(String source) {
    this.source = source;
    lines = Arrays.asList(source.replace("\r\n", "\n").split("\n", -1));
    boolean[] text = VaultMarkdown.textLines(lines.toArray(String[]::new));
    for (var e : SportsDiary.EXERCISES) {
      String key = e.planKey();
      values.put(key, e.fallback());
      Pattern field = Pattern.compile("^[ \\t]*" + Pattern.quote(key) + "::[ \\t]*(.*)$");
      for (int i = 0; i < lines.size(); i++) {
        if (!text[i]) continue;
        var match = field.matcher(lines.get(i));
        if (!match.matches()) continue;
        if (indices.putIfAbsent(key, i) != null)
          throw new IllegalArgumentException("Повторяющийся ключ: " + key);
        try {
          var sets = SportsDiary.parse(e, match.group(1));
          if (!sets.isEmpty()) values.put(key, validate(e, Numbers.plain(sets.get(0).first())));
        } catch (IllegalArgumentException malformed) {
          // Invalid file values fall back for this exercise only; reading never rewrites the file.
        }
      }
    }
  }

  public static double validate(SportsDiary.Exercise e, String value) {
    double number = SportsDiary.positive(value, true);
    if (!e.bike() && number != Math.rint(number))
      throw new IllegalArgumentException("Введите целое количество повторений");
    return number;
  }

  public String value(SportsDiary.Exercise e) {
    return Numbers.plain(values.get(e.planKey()));
  }

  public SportsDiary.Set defaultApproach(SportsDiary.Exercise e) {
    double count = values.get(e.planKey());
    return new SportsDiary.Set(count, e.paired() ? Double.valueOf(count) : null);
  }

  public String update(SportsDiary.Exercise e, String raw) {
    double count = validate(e, raw);
    String one = new SportsDiary.Set(count, e.paired() ? Double.valueOf(count) : null).format(e);
    // Cycling keeps its single duration; repetitions illustrate two identical approaches.
    String value = e.bike() ? one : one + "|" + one;
    List<String> changed = new ArrayList<>(lines);
    Integer i = indices.get(e.planKey());
    if (i == null) {
      boolean[] text = VaultMarkdown.textLines(lines.toArray(String[]::new));
      int insert = 0;
      while (insert < text.length && !text[insert]) insert++;
      if (insert == text.length)
        throw new IllegalArgumentException("Не найдено место для ключа вне защищённой разметки");
      changed.add(insert, e.planKey() + ":: " + value);
    } else {
      String line = lines.get(i);
      changed.set(i, line.substring(0, line.indexOf("::") + 2) + " " + value);
    }
    return String.join(source.contains("\r\n") ? "\r\n" : "\n", changed);
  }
}
