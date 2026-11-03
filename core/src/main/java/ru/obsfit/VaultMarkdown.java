package ru.obsfit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** Pure Markdown engine, shared storage is handled separately. No Android dependencies. */
public final class VaultMarkdown {
  private VaultMarkdown() {}

  public static final List<String> SECTIONS =
      List.of(
          "Заметки",
          "Завтрак",
          "Перекус 1",
          "Обед",
          "Перекус 2",
          "Второй обед",
          "Перекус 3",
          "Ужин",
          "Перекус 4");

  public static String sectionName(String name) {
    return "Перекусы".equals(name) ? "Перекус 4" : name;
  }

  private static final String NUM = "\\d+(?:[.,]\\d+)?";
  private static final Pattern READY =
      Pattern.compile(
          "^(.+?)\\s+(" + NUM + ")\\s*г\\s+(" + NUM + ")\\s+ккал$",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern WEIGHTED =
      Pattern.compile(
          "^(.+?)\\s+(" + NUM + ")(?:\\s*г)?$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern MANUAL =
      Pattern.compile(
          "^(?:(.*?)\\s+)?(" + NUM + ")\\s+ккал$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
  private static final Pattern PREFIX =
      Pattern.compile("^(\\s*(?:(?:[-+*]|\\d+[.)])\\s+)?)(.*?)(\\s*)$");
  public static final String START = "<!-- food-table:start -->", END = "<!-- food-table:end -->";

  public static String key(String text) {
    return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.forLanguageTag("ru"));
  }

  public static String fmt(double n) {
    return BigDecimal.valueOf(Math.round(n * 100d) / 100d).stripTrailingZeros().toPlainString();
  }

  public static double number(String s) {
    if (!s.trim().matches(NUM))
      throw new IllegalArgumentException("Требуется неотрицательное число: «" + s + "»");
    double value = Double.parseDouble(s.trim().replace(',', '.'));
    if (!Double.isFinite(value) || value > 1_000_000_000)
      throw new IllegalArgumentException("Слишком большое число");
    return value;
  }

  public static final class Food {
    public final String name, ingredients;
    public final List<String> aliases;
    public double kcal100, weight;

    public Food(
        String name, List<String> aliases, String ingredients, double kcal100, double weight) {
      this.name = name.trim();
      this.aliases = List.copyOf(aliases);
      this.ingredients = ingredients.trim();
      this.kcal100 = kcal100;
      this.weight = weight;
    }

    public List<String> names() {
      List<String> names = new ArrayList<>();
      names.add(name);
      names.addAll(aliases);
      return names;
    }
  }

  public static final class Catalog {
    public final List<Food> foods;
    public final Map<String, Food> byAlias;
    public final String source;

    Catalog(List<Food> foods, Map<String, Food> byAlias, String source) {
      this.foods = foods;
      this.byAlias = byAlias;
      this.source = source;
    }

    public Food get(String name) {
      return byAlias.get(key(name));
    }
  }

  private static List<String> br(String value) {
    List<String> result = new ArrayList<>();
    for (String item : value.split("(?i)<br\\s*/?\\s*>"))
      if (!item.trim().isEmpty()) result.add(item.trim());
    return result;
  }

  private static String[] cells(String row) {
    String trimmed = row.trim();
    if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
    if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
    return Arrays.stream(trimmed.split("\\|", -1)).map(String::trim).toArray(String[]::new);
  }

  private static void validName(String name) {
    if (name.isBlank()
        || Pattern.compile("[`|<>\\[\\]~\\r\\n]").matcher(name).find()
        || name.matches("^[-+*#\\d].*")
        || name.contains("::"))
      throw new IllegalArgumentException("Недопустимое название или алиас: «" + name + "»");
  }

  public static Catalog catalog(String content) {
    String[] lines = content.replace("\r\n", "\n").split("\n", -1);
    int start = Arrays.asList(lines).indexOf(START), end = Arrays.asList(lines).indexOf(END);
    if (start < 0 || end <= start)
      throw new IllegalArgumentException("В Еда.md не найдены маркеры таблицы еды");
    List<String> rows = new ArrayList<>();
    for (int i = start + 1; i < end; i++) if (!lines[i].isBlank()) rows.add(lines[i]);
    if (rows.size() < 2
        || cells(rows.get(0)).length != 4
        || cells(rows.get(1)).length != 4
        || !Arrays.stream(cells(rows.get(1))).allMatch(s -> s.matches(":?-{3,}:?")))
      throw new IllegalArgumentException("Еда.md: нужна таблица с четырьмя столбцами");
    List<Food> foods = new ArrayList<>();
    Map<String, Food> index = new LinkedHashMap<>();
    for (String row : rows.subList(2, rows.size())) {
      String[] c = cells(row);
      if (c.length != 4) throw new IllegalArgumentException("Еда.md: неверное число столбцов");
      List<String> names = br(c[0]);
      if (names.isEmpty()) throw new IllegalArgumentException("Еда.md: пустое название");
      Food food =
          new Food(
              names.get(0),
              names.subList(1, names.size()),
              c[1],
              c[1].isEmpty() ? number(c[2]) : 0,
              c[1].isEmpty() ? number(c[3]) : 0);
      if (food.ingredients.isEmpty() && food.weight <= 0)
        throw new IllegalArgumentException(food.name + ": вес должен быть больше нуля");
      for (String name : food.names()) {
        validName(name);
        if (index.putIfAbsent(key(name), food) != null)
          throw new IllegalArgumentException("Еда.md: повтор названия или алиаса «" + name + "»");
      }
      foods.add(food);
    }
    Catalog result = new Catalog(List.copyOf(foods), Collections.unmodifiableMap(index), content);
    Set<Food> calculated = new HashSet<>();
    List<Food> visiting = new ArrayList<>();
    for (Food food : foods) calculateRecipe(result, food, calculated, visiting);
    return result;
  }

  private static void calculateRecipe(
      Catalog catalog, Food food, Set<Food> calculated, List<Food> visiting) {
    if (calculated.contains(food)) return;
    int cycle = visiting.indexOf(food);
    if (cycle >= 0) {
      List<String> names = new ArrayList<>();
      for (Food item : visiting.subList(cycle, visiting.size())) names.add(item.name);
      names.add(food.name);
      throw new IllegalArgumentException("Циклический состав: " + String.join(" → ", names));
    }
    if (!food.ingredients.isEmpty()) {
      visiting.add(food);
      List<RecipeIngredients.Item> items = RecipeIngredients.parse(catalog, food.ingredients);
      for (RecipeIngredients.Item item : items)
        calculateRecipe(catalog, catalog.get(item.name()), calculated, visiting);
      RecipeIngredients.Nutrition nutrition = RecipeIngredients.nutrition(catalog, items);
      food.weight = nutrition.weight();
      food.kcal100 = nutrition.kcal100();
      visiting.remove(visiting.size() - 1);
    }
    calculated.add(food);
  }

  public static Set<String> replacementNames(String content) {
    String[] blocks = content.split("---", -1);
    if (blocks.length < 2)
      throw new IllegalArgumentException("Замены.md: не найден разделитель ---");
    Set<String> values = new HashSet<>();
    boolean group = false;
    for (String line : blocks[1].split("\\R")) {
      String s = line.trim();
      if (s.startsWith("### ")) {
        group = true;
        values.add(key(s.substring(4)));
      } else if (group && !s.isBlank()) values.add(key(s));
    }
    return values;
  }

  public static String addFood(Catalog catalog, Food food, String replacements) {
    if (!food.ingredients.isEmpty()) return putFood(catalog, null, food, replacements);
    if (food.weight <= 0
        || !Double.isFinite(food.weight)
        || food.kcal100 < 0
        || !Double.isFinite(food.kcal100))
      throw new IllegalArgumentException("Проверьте вес и калорийность");
    Set<String> names = new HashSet<>(), other = replacementNames(replacements);
    for (String name : food.names()) {
      validName(name);
      String k = key(name);
      if (!names.add(k) || catalog.byAlias.containsKey(k))
        throw new IllegalArgumentException("Название или алиас уже есть в Еда.md: «" + name + "»");
      if (other.contains(k))
        throw new IllegalArgumentException(
            "«"
                + name
                + "» уже есть в Замены.md. Уберите повтор или выберите другое название/алиас.");
    }
    String eol = catalog.source.contains("\r\n") ? "\r\n" : "\n";
    int end = catalog.source.indexOf(END);
    String before = catalog.source.substring(0, end).stripTrailing();
    String row =
        "| "
            + String.join("<br>", food.names())
            + " | | "
            + fmt(food.kcal100)
            + " | "
            + fmt(food.weight)
            + " |";
    String result = before + eol + row + eol + eol + catalog.source.substring(end);
    catalog(result);
    return result;
  }

  /** Table-only replacement. Instructions and all content outside the markers are preserved. */
  private static String foodTable(Catalog original, List<Food> foods) {
    List<String> lines = new ArrayList<>(Arrays.asList(lines(original.source)));
    int start = lines.indexOf(START), end = lines.indexOf(END);
    List<String> oldRows = new ArrayList<>();
    for (int i = start + 1; i < end; i++) if (!lines.get(i).isBlank()) oldRows.add(lines.get(i));
    List<String> rows = new ArrayList<>();
    rows.add("");
    rows.add(oldRows.get(0));
    rows.add(oldRows.get(1));
    for (Food food : foods)
      rows.add(
          "| "
              + String.join("<br>", food.names())
              + " | "
              + food.ingredients
              + " | "
              + fmt(food.kcal100)
              + " | "
              + fmt(food.weight)
              + " |");
    rows.add("");
    lines.subList(start + 1, end).clear();
    lines.addAll(start + 1, rows);
    return join(lines, original.source);
  }

  private static Food catalogTarget(Catalog catalog, String name) {
    Food food = catalog.get(name);
    if (food == null || !key(food.name).equals(key(name)))
      throw new IllegalStateException("Продукт изменился. Обновите справочник.");
    return food;
  }

  private static String ingredientReferences(
      Catalog catalog, Food target, String ingredients, String newName) {
    List<String> values = new ArrayList<>();
    Pattern pattern =
        Pattern.compile(
            "^(.+?)\\s+(" + NUM + ")\\s*г$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    for (String ingredient : br(ingredients)) {
      Matcher m = pattern.matcher(ingredient);
      values.add(
          m.matches() && catalog.get(m.group(1)) == target
              ? newName + " " + m.group(2) + " г"
              : ingredient);
    }
    return String.join("<br>", values);
  }

  /**
   * Rename also keeps the old full name as an alias so existing diary entries remain recognizable.
   */
  public static String putFood(
      Catalog catalog, String originalName, Food replacement, String replacements) {
    Food old = originalName == null ? null : catalogTarget(catalog, originalName);
    List<String> aliases = new ArrayList<>(replacement.aliases);
    if (old != null
        && !key(old.name).equals(key(replacement.name))
        && aliases.stream().noneMatch(a -> key(a).equals(key(old.name)))) aliases.add(old.name);
    Food updated =
        new Food(
            replacement.name,
            aliases,
            replacement.ingredients,
            replacement.kcal100,
            replacement.weight);
    Set<String> names = new HashSet<>(), other = replacementNames(replacements);
    for (String name : updated.names()) {
      validName(name);
      String normalized = key(name);
      Food existing = catalog.get(name);
      if (!names.add(normalized) || existing != null && existing != old)
        throw new IllegalArgumentException("Название или алиас уже есть в Еда.md: «" + name + "»");
      if (other.contains(normalized))
        throw new IllegalArgumentException(
            "«"
                + name
                + "» уже есть в Замены.md. Уберите повтор или выберите другое название/алиас.");
    }
    if (updated.ingredients.isEmpty()
        && (updated.weight <= 0
            || !Double.isFinite(updated.weight)
            || updated.kcal100 < 0
            || !Double.isFinite(updated.kcal100)))
      throw new IllegalArgumentException("Проверьте вес и калорийность");
    List<Food> foods = new ArrayList<>();
    for (Food item : catalog.foods) {
      if (item == old) foods.add(updated);
      else
        foods.add(
            new Food(
                item.name,
                item.aliases,
                old == null
                    ? item.ingredients
                    : ingredientReferences(catalog, old, item.ingredients, updated.name),
                item.kcal100,
                item.weight));
    }
    if (old == null) foods.add(updated);
    Catalog calculated = catalog(foodTable(catalog, foods));
    return foodTable(catalog, calculated.foods);
  }

  public static String deleteFood(Catalog catalog, String originalName) {
    Food target = catalogTarget(catalog, originalName);
    List<String> dependents = new ArrayList<>();
    for (Food item : catalog.foods)
      if (item != target && !item.ingredients.isEmpty()) {
        Pattern ingredient =
            Pattern.compile(
                "^(.+?)\\s+(" + NUM + ")\\s*г$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        for (String part : br(item.ingredients)) {
          Matcher m = ingredient.matcher(part);
          if (m.matches() && catalog.get(m.group(1)) == target) {
            dependents.add(item.name);
            break;
          }
        }
      }
    if (!dependents.isEmpty())
      throw new IllegalArgumentException(
          "Нельзя удалить «"
              + target.name
              + "»: используется в составе «"
              + String.join("», «", dependents)
              + "». Сначала измените состав этих блюд.");
    List<Food> foods = new ArrayList<>(catalog.foods);
    foods.remove(target);
    String updated = foodTable(catalog, foods);
    catalog(updated);
    return updated;
  }

  public static final class Entry {
    public final int line;
    public final String raw, name, section, prefix, suffix;
    public final Double weight;
    public final double kcal;
    public final Food food;

    Entry(
        int line,
        String raw,
        String name,
        String section,
        String prefix,
        String suffix,
        Double weight,
        double kcal,
        Food food) {
      this.line = line;
      this.raw = raw;
      this.name = name;
      this.section = section;
      this.prefix = prefix;
      this.suffix = suffix;
      this.weight = weight;
      this.kcal = kcal;
      this.food = food;
    }

    public String canonical() {
      return prefix
          + (weight == null
              ? (name.isBlank() ? "" : name + " ") + fmt(kcal) + " ккал"
              : name + " " + fmt(weight) + " г " + fmt(kcal) + " ккал")
          + suffix;
    }
  }

  public static final class Day {
    public final String source;
    public final List<Entry> entries;
    public final double total, dailyLimit;

    public Day(String source, List<Entry> entries) {
      this.source = source;
      this.dailyLimit = dailyLimit(source);
      this.entries = List.copyOf(entries);
      this.total = entries.stream().mapToDouble(e -> e.kcal).sum();
    }

    public List<Entry> section(String name) {
      name = sectionName(name);
      List<Entry> items = new ArrayList<>();
      for (Entry e : entries) if (e.section.equals(name)) items.add(e);
      return items;
    }
  }

  /**
   * Marks only Markdown text: code, frontmatter, quotes, HTML and Obsidian comments are excluded.
   */
  private static boolean[] textLines(String[] lines) {
    boolean[] result = new boolean[lines.length];
    String fence = null;
    boolean front = false, html = false, comment = false;
    for (int i = 0; i < lines.length; i++) {
      String s = lines[i].trim();
      if (i == 0 && s.equals("---")) {
        front = true;
        continue;
      }
      if (front) {
        if (s.equals("---")) front = false;
        continue;
      }
      if (fence != null) {
        Matcher close = Pattern.compile("^(`{3,}|~{3,})\\s*$").matcher(s);
        if (close.matches()
            && close.group(1).charAt(0) == fence.charAt(0)
            && close.group(1).length() >= fence.length()) fence = null;
        continue;
      }
      if (html || s.contains("<!--")) {
        html = !s.contains("-->");
        continue;
      }
      if (comment || s.contains("%%")) {
        if ((s.split("%%", -1).length - 1) % 2 == 1) comment = !comment;
        continue;
      }
      if (s.startsWith(">")) continue;
      Matcher f = Pattern.compile("^(`{3,}|~{3,})").matcher(s);
      if (f.find()) {
        fence = f.group();
        continue;
      }
      result[i] = true;
    }
    return result;
  }

  public static final int DEFAULT_DAILY_CALORIES = 2150;

  /** Only a single positive inline field in the sports section is authoritative. */
  public static double dailyLimit(String source) {
    String[] lines = source.replace("\r\n", "\n").split("\n", -1);
    boolean[] text = textLines(lines);
    int sportsLevel = 0, fields = 0;
    double limit = DEFAULT_DAILY_CALORIES;
    for (int i = 0; i < lines.length; i++) {
      if (!text[i]) continue;
      Matcher heading = HEADING.matcher(lines[i].trim());
      if (heading.matches()) {
        int level = heading.group(1).length();
        if (heading.group(2).equals("Спорт и диета")) sportsLevel = level;
        else if (sportsLevel > 0 && level <= sportsLevel) sportsLevel = 0;
        continue;
      }
      if (sportsLevel == 0) continue;
      Matcher field =
          Pattern.compile("^\\s*СуточнаяНормаКалорий::\\s*(.*?)\\s*$").matcher(lines[i]);
      if (!field.matches()) continue;
      fields++;
      try {
        double candidate = number(field.group(1));
        limit = candidate > 0 ? candidate : DEFAULT_DAILY_CALORIES;
      } catch (IllegalArgumentException error) {
        limit = DEFAULT_DAILY_CALORIES;
      }
    }
    return fields == 1 ? limit : DEFAULT_DAILY_CALORIES;
  }

  public static Day day(String source, Catalog catalog) {
    String[] lines = source.replace("\r\n", "\n").split("\n", -1);
    boolean[] text = textLines(lines);
    List<Entry> entries = new ArrayList<>();
    String section = "Заметки";
    for (int i = 0; i < lines.length; i++) {
      String raw = lines[i], s = raw.trim();
      if (!text[i]) continue;
      Matcher heading = HEADING.matcher(s);
      if (heading.matches()) {
        String headingSection = sectionName(heading.group(2));
        section = SECTIONS.contains(headingSection) ? headingSection : "Заметки";
        continue;
      }
      if (s.startsWith("|")
          || s.contains("::")
          || s.contains("`")
          || s.contains("~~")
          || s.endsWith("--")) continue;
      Matcher p = PREFIX.matcher(raw);
      if (!p.matches()) continue;
      String body = p.group(2), name = null;
      Double grams = null;
      double kcal = 0;
      Food food = null;
      Matcher r = READY.matcher(body);
      if (r.matches()) {
        name = r.group(1);
        grams = number(r.group(2));
        kcal = number(r.group(3));
        food = catalog.get(name);
        if (food != null) {
          name = food.name;
          kcal = Math.round(food.kcal100 * grams / 100);
        }
      } else {
        Matcher m = MANUAL.matcher(body);
        if (m.matches()) {
          name = m.group(1) == null ? "" : m.group(1);
          kcal = number(m.group(2));
        } else {
          food = catalog.get(body);
          if (food != null) {
            name = food.name;
            grams = food.weight;
          } else {
            Matcher w = WEIGHTED.matcher(body);
            if (w.matches()) {
              food = catalog.get(w.group(1));
              if (food != null) {
                name = food.name;
                grams = number(w.group(2));
              }
            }
          }
          if (food != null) kcal = Math.round(food.kcal100 * grams / 100);
        }
      }
      if (name != null)
        entries.add(new Entry(i, raw, name, section, p.group(1), p.group(3), grams, kcal, food));
    }
    return new Day(source, entries);
  }

  public static String portion(String name, double grams, double kcal100) {
    validName(name);
    if (grams <= 0 || !Double.isFinite(grams) || kcal100 < 0 || !Double.isFinite(kcal100))
      throw new IllegalArgumentException("Проверьте вес и калорийность");
    return name.trim() + " " + fmt(grams) + " г " + Math.round(grams * kcal100 / 100) + " ккал";
  }

  private static String[] lines(String source) {
    return source.replace("\r\n", "\n").split("\n", -1);
  }

  private static String join(List<String> lines, String original) {
    return String.join(original.contains("\r\n") ? "\r\n" : "\n", lines);
  }

  public static String recalculate(String source, Catalog catalog) {
    Day day = day(source, catalog);
    List<String> lines = new ArrayList<>(Arrays.asList(lines(source)));
    boolean[] text = textLines(lines.toArray(String[]::new));
    for (Entry entry : day.entries) lines.set(entry.line, entry.canonical());
    boolean found = false;
    for (int i = 0; i < lines.size(); i++)
      if (text[i] && lines.get(i).matches("^\\s*СъеденоКалорий::.*$")) {
        lines.set(i, lines.get(i).replaceFirst("(::).*$", "$1 " + fmt(day.total)));
        found = true;
      }
    if (!found) {
      int index = findHeading(lines, "Спорт и диета", 3);
      if (index < 0) {
        ensureBlank(lines);
        lines.add("### Спорт и диета");
        lines.add("");
        index = lines.size() - 1;
      }
      lines.add(index + 1, "СъеденоКалорий:: " + fmt(day.total));
    }
    return join(lines, source);
  }

  public static String edit(String source, Entry entry, String replacement, Catalog catalog) {
    List<String> lines = new ArrayList<>(Arrays.asList(lines(source)));
    if (entry.line >= lines.size() || !lines.get(entry.line).equals(entry.raw))
      throw new IllegalStateException("Запись изменилась. Обновите день и повторите действие.");
    if (replacement == null) lines.remove(entry.line);
    else lines.set(entry.line, entry.prefix + replacement + entry.suffix);
    return recalculate(join(lines, source), catalog);
  }

  public static String move(String source, Entry entry, String target, Catalog catalog) {
    target = sectionName(target);
    if (!SECTIONS.contains(target)) throw new IllegalArgumentException("Неизвестный раздел");
    String removed = edit(source, entry, null, catalog);
    return add(removed, target, entry.canonical().trim(), catalog);
  }

  private static int findHeading(List<String> lines, String name, int level) {
    boolean[] text = textLines(lines.toArray(String[]::new));
    for (int i = 0; i < lines.size(); i++) {
      if (!text[i]) continue;
      Matcher m = HEADING.matcher(lines.get(i).trim());
      if (m.matches() && m.group(1).length() == level && m.group(2).equals(name)) return i;
    }
    return -1;
  }

  private static int sectionEnd(List<String> lines, int start, int level) {
    boolean[] text = textLines(lines.toArray(String[]::new));
    for (int i = start + 1; i < lines.size(); i++) {
      if (!text[i]) continue;
      Matcher m = HEADING.matcher(lines.get(i).trim());
      if (m.matches() && m.group(1).length() <= level) return i;
    }
    return lines.size();
  }

  private static void ensureBlank(List<String> lines) {
    if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
  }

  public static String add(String source, String section, String entry, Catalog catalog) {
    section = sectionName(section);
    if (!SECTIONS.contains(section) || entry.contains("\n") || entry.contains("\r"))
      throw new IllegalArgumentException("Некорректная запись");
    List<String> lines = new ArrayList<>(Arrays.asList(lines(source)));
    int level = section.equals("Заметки") ? 2 : 3;
    int start = findHeading(lines, section, level);
    if (start < 0 && section.equals("Перекус 4")) start = findHeading(lines, "Перекусы", level);
    if (start < 0) {
      if (level == 2) {
        ensureBlank(lines);
        lines.add("## Заметки");
        lines.add("");
        start = lines.size() - 2;
      } else {
        int parent = findHeading(lines, "Питание", 2);
        if (parent < 0) {
          ensureBlank(lines);
          lines.add("## Питание");
          lines.add("");
          parent = lines.size() - 2;
        }
        int at = sectionEnd(lines, parent, 2);
        boolean[] normal = textLines(lines.toArray(String[]::new));
        for (int i = parent + 1; i < at; i++) {
          if (!normal[i]) continue;
          Matcher heading = HEADING.matcher(lines.get(i).trim());
          if (heading.matches() && heading.group(1).length() == 3) {
            int order = SECTIONS.indexOf(sectionName(heading.group(2)));
            if (order > SECTIONS.indexOf(section)) {
              at = i;
              break;
            }
          }
        }
        lines.addAll(at, List.of("", "### " + section, ""));
        start = at + 1;
      }
    }
    int at = sectionEnd(lines, start, level);
    List<String> insert = new ArrayList<>();
    if (at > 0 && !lines.get(at - 1).isBlank()) insert.add("");
    insert.add(entry);
    insert.add("");
    lines.addAll(at, insert);
    return recalculate(join(lines, source), catalog);
  }

  public static LocalDate date(String name) {
    if (name.contains(".sync-conflict")) return null;
    if (!name.matches("\\d{4}-\\d{2}-\\d{2}.*\\.md")) return null;
    try {
      return LocalDate.parse(name.substring(0, 10));
    } catch (Exception e) {
      return null;
    }
  }
}
