package ru.obsfit;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * Rolling diary occurrence counts. Replacing a whole date makes retries, moves and deletes
 * idempotent.
 */
public final class MealUsage {
  public static final int DAYS = 14;
  private static final String VERSION = "meal-usage-v1";
  private final NavigableMap<LocalDate, Map<String, Map<String, Integer>>> days = new TreeMap<>();
  private LocalDate rebuilt;

  private static String group(String section) {
    String canonical = VaultMarkdown.sectionName(section);
    return canonical.matches("Перекус [1-4]") ? "Перекусы" : canonical;
  }

  private static boolean includes(LocalDate date, LocalDate today) {
    return !date.isBefore(today.minusDays(DAYS - 1)) && !date.isAfter(today);
  }

  private void prune(LocalDate today) {
    days.keySet().removeIf(date -> !includes(date, today));
  }

  public boolean rebuilt() {
    return rebuilt != null;
  }

  public void markRebuilt(LocalDate today) {
    rebuilt = today;
  }

  public void replaceDay(LocalDate date, List<VaultMarkdown.Entry> entries, LocalDate today) {
    prune(today);
    if (!includes(date, today)) return;
    Map<String, Map<String, Integer>> counts = new TreeMap<>();
    for (var entry : entries) {
      if (entry.food == null)
        continue; // Manual calories and unknown products have no catalog identity.
      counts
          .computeIfAbsent(group(entry.section), ignored -> new TreeMap<>())
          .merge(VaultMarkdown.key(entry.food.name), 1, Integer::sum);
    }
    if (counts.isEmpty()) days.remove(date);
    else days.put(date, counts);
  }

  public long frequency(VaultMarkdown.Food food, String section, LocalDate today) {
    Set<String> names = new HashSet<>();
    for (String name : food.names()) names.add(VaultMarkdown.key(name));
    long result = 0;
    for (var day : days.entrySet()) {
      if (!includes(day.getKey(), today)) continue;
      var counts = day.getValue().getOrDefault(group(section), Map.of());
      for (String name : names) result += counts.getOrDefault(name, 0);
    }
    return result;
  }

  /**
   * Single bounded cache value, overwritten after each successful diary update; never an event log.
   */
  public String encode(LocalDate today) {
    prune(today);
    StringBuilder out =
        new StringBuilder(VERSION)
            .append('\n')
            .append(rebuilt == null ? "-" : rebuilt)
            .append('\n');
    for (var day : days.entrySet())
      for (var group : day.getValue().entrySet())
        for (var food : group.getValue().entrySet())
          out.append(day.getKey())
              .append('\t')
              .append(pack(group.getKey()))
              .append('\t')
              .append(pack(food.getKey()))
              .append('\t')
              .append(food.getValue())
              .append('\n');
    return out.toString();
  }

  public static MealUsage decode(String encoded, LocalDate today) {
    MealUsage result = new MealUsage();
    if (encoded == null) return result;
    try {
      String[] lines = encoded.split("\n");
      if (lines.length < 2 || !VERSION.equals(lines[0])) return result;
      if (!lines[1].equals("-")) result.rebuilt = LocalDate.parse(lines[1]);
      for (int i = 2; i < lines.length; i++) {
        String[] fields = lines[i].split("\t", -1);
        if (fields.length != 4) throw new IllegalArgumentException("Invalid cache row");
        LocalDate date = LocalDate.parse(fields[0]);
        String section = unpack(fields[1]), name = unpack(fields[2]);
        int count = Integer.parseInt(fields[3]);
        if (count <= 0
            || name.isBlank()
            || !(VaultMarkdown.SECTIONS.contains(section) || section.equals("Перекусы")))
          throw new IllegalArgumentException("Invalid cache value");
        if (!includes(date, today)) continue;
        var counts =
            result
                .days
                .computeIfAbsent(date, ignored -> new TreeMap<>())
                .computeIfAbsent(group(section), ignored -> new TreeMap<>());
        if (counts.put(name, count) != null)
          throw new IllegalArgumentException("Duplicate cache row");
      }
      return result;
    } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
      return new MealUsage(); // Derived data can always be rebuilt with Refresh.
    }
  }

  private static String pack(String text) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String unpack(String text) {
    return new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
  }
}
