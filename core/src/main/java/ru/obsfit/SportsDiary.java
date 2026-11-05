package ru.obsfit;

import java.util.*;
import java.util.regex.*;

/** Sports-only Markdown edits. Other sections, comments, food and calorie fields stay untouched. */
public final class SportsDiary {
  public record Exercise(String key, String name, boolean paired, boolean bike, String preset) {}

  public static final List<Exercise> EXERCISES =
      List.of(
          new Exercise("Отжимания", "Отжимания", false, false, "35"),
          new Exercise("ГантелиБицепс", "Бицепс · 11 кг", true, false, "15+15"),
          new Exercise("ГантелиТрицепсВверх", "Гантель вверх · 11 кг", true, false, "15+15"),
          new Exercise("Пресс", "Пресс", false, false, "25"),
          new Exercise("Приседания", "Приседания", false, false, "40"),
          new Exercise("Велотренажер", "Велотренажёр", false, true, "20 мин"));
  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
  private static final String NUM = "\\d+(?:[.,]\\d+)?";

  private SportsDiary() {}

  /** First is reps or minutes; second is the other arm or explicitly reported bike kcal. */
  public record Set(double first, Double second) {
    public String format(Exercise e) {
      String a = VaultMarkdown.fmt(first);
      return e.bike
          ? a + " мин" + (second == null ? "" : " " + VaultMarkdown.fmt(second) + " ккал")
          : a + (e.paired ? "+" + VaultMarkdown.fmt(second) : "");
    }
  }

  public static final class Form {
    public String first, second;

    public Form(Set set) {
      first = VaultMarkdown.fmt(set.first);
      second = set.second == null ? "" : VaultMarkdown.fmt(set.second);
    }

    public Set value(Exercise e) {
      double a = positive(first, false);
      Double b = null;
      if (e.paired || e.bike && !second.isBlank()) b = positive(second, false);
      if (!e.bike && (a != Math.rint(a) || b != null && b != Math.rint(b)))
        throw new IllegalArgumentException("Повторения должны быть целыми числами");
      return new Set(a, b);
    }
  }

  public static double positive(String raw, boolean strictly) {
    String s = raw.trim();
    if (!s.matches(NUM)) throw new IllegalArgumentException("Введите число");
    double value = Double.parseDouble(s.replace(',', '.'));
    if (!Double.isFinite(value) || value > 1_000_000 || strictly && value <= 0)
      throw new IllegalArgumentException("Некорректное значение");
    return value;
  }

  public static List<Set> parse(Exercise e, String raw) {
    if (raw.isBlank()) return List.of();
    List<Set> result = new ArrayList<>();
    for (String part : raw.split("\\|", -1)) {
      String s = part.trim();
      if (e.bike) {
        Matcher m =
            Pattern.compile(
                    "^(" + NUM + ")(?:\\s*мин(?:\\.|ут(?:а|ы)?)?)?(?:\\s+(" + NUM + ")\\s*ккал)?$",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(s);
        if (!m.matches()) throw new IllegalArgumentException("Ожидается: 20 мин 120 ккал");
        result.add(
            new Form(
                    new Set(
                        positive(m.group(1), false),
                        m.group(2) == null ? null : positive(m.group(2), false)))
                .value(e));
      } else {
        String[] arms = s.split("[+,]", -1);
        if (arms.length != (e.paired ? 2 : 1))
          throw new IllegalArgumentException(
              e.paired ? "Ожидается: 15+15|15+15" : "Ожидается: 35|35");
        result.add(
            new Form(new Set(positive(arms[0], false), e.paired ? positive(arms[1], false) : null))
                .value(e));
      }
    }
    return List.copyOf(result);
  }

  public static Set preset(Exercise e, String raw) {
    List<Set> sets = parse(e, raw);
    if (sets.size() != 1) throw new IllegalArgumentException("Задайте один подход");
    return sets.get(0);
  }

  private record Fields(List<String> lines, int heading, int end, Map<String, Integer> indices) {}

  private static Fields fields(String source) {
    List<String> lines =
        new ArrayList<>(Arrays.asList(source.replace("\r\n", "\n").split("\n", -1)));
    boolean[] text = VaultMarkdown.textLines(lines.toArray(String[]::new));
    int start = -1, end = lines.size(), level = 0;
    Map<String, Integer> indices = new HashMap<>();
    for (int i = 0; i < lines.size(); i++) {
      if (!text[i]) continue;
      Matcher h = HEADING.matcher(lines.get(i).trim());
      if (h.matches()) {
        if (h.group(2).equals("Спорт и диета")) {
          if (start >= 0) throw new IllegalArgumentException("Два раздела «Спорт и диета»");
          start = i;
          level = h.group(1).length();
        } else if (start >= 0 && end == lines.size() && h.group(1).length() <= level) end = i;
      } else if (start >= 0 && i < end) {
        Matcher field = Pattern.compile("^\\s*([^:]+)::[ \\t]*(.*)$").matcher(lines.get(i));
        if (field.matches() && indices.putIfAbsent(field.group(1).trim(), i) != null)
          throw new IllegalArgumentException("Повторяющийся ключ: " + field.group(1).trim());
      }
    }
    return new Fields(lines, start, end, indices);
  }

  public static String read(String source, String key) {
    Fields f = fields(source);
    Integer index = f.indices.get(key);
    return index == null
        ? ""
        : f.lines.get(index).substring(f.lines.get(index).indexOf("::") + 2).trim();
  }

  public static String write(String source, String key, String value) {
    if (!key.equals("Вес") && EXERCISES.stream().noneMatch(e -> e.key.equals(key)))
      throw new IllegalArgumentException("Неизвестный ключ");
    if (value.contains("\n") || value.contains("\r"))
      throw new IllegalArgumentException("Нужна одна строка");
    Fields f = fields(source);
    Integer index = f.indices.get(key);
    if (index != null)
      f.lines.set(
          index,
          f.lines.get(index).replaceFirst("(::).*$", "$1" + (value.isBlank() ? "" : " " + value)));
    else if (!value.isBlank()) {
      if (f.heading < 0) {
        f.lines.add("");
        f.lines.add("### Спорт и диета");
        f.lines.add("");
        f.lines.add(key + ":: " + value);
      } else f.lines.add(f.heading + 1, key + ":: " + value);
    }
    return String.join(source.contains("\r\n") ? "\r\n" : "\n", f.lines);
  }

  public static String changeSet(String source, Exercise e, int index, Set replacement) {
    List<Set> sets = new ArrayList<>(parse(e, read(source, e.key)));
    if (index < 0 || index > sets.size() || index == sets.size() && replacement == null)
      throw new IllegalArgumentException("Подход больше не найден");
    if (replacement == null) sets.remove(index);
    else {
      Set validated = new Form(replacement).value(e);
      if (index == sets.size()) sets.add(validated);
      else sets.set(index, validated);
    }
    return write(source, e.key, String.join("|", sets.stream().map(s -> s.format(e)).toList()));
  }

  public static String weight(String source, String raw) {
    return write(source, "Вес", raw.isBlank() ? "" : VaultMarkdown.fmt(positive(raw, true)));
  }
}
