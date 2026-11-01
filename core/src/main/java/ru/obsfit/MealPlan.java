package ru.obsfit;

import java.time.*;
import java.util.*;

/** Daily budget for the user’s fixed schedule. No Android or I/O. */
public final class MealPlan {
  public static final int DAILY = 2600;

  public record Slot(String name, String section, LocalTime start, LocalTime end, int calories) {}

  public record Eaten(String section, double calories) {}

  public record Guidance(
      Slot now,
      int available,
      Slot next,
      int nextCalories,
      LocalDateTime nextAt,
      double dailyRemaining,
      boolean approximate,
      boolean ended) {}

  public static final List<Slot> SLOTS =
      List.of(
          slot("Завтрак", "Завтрак", "09:00", "10:00", 750),
          slot("Перекус", "Перекусы", "11:00", "12:00", 100),
          slot("Обед", "Обед", "13:00", "14:00", 550),
          slot("Перекус", "Перекусы", "15:00", "16:00", 100),
          slot("Второй обед", "Второй обед", "17:00", "18:00", 500),
          slot("Ужин", "Ужин", "19:00", "20:00", 500),
          slot("Перекус", "Перекусы", "21:00", "22:00", 100));

  private static Slot slot(String name, String section, String from, String to, int kcal) {
    return new Slot(name, section, LocalTime.parse(from), LocalTime.parse(to), kcal);
  }

  public static Guidance guidance(LocalDate date, LocalDateTime clock, List<Eaten> entries) {
    LocalTime time = clock.toLocalTime();
    double[] consumed = new double[SLOTS.size()];
    double total = 0, unassigned = 0;
    boolean approximate = false;
    for (Eaten entry : entries) {
      if (!Double.isFinite(entry.calories) || entry.calories < 0)
        throw new IllegalArgumentException("Invalid calories");
      total += entry.calories;
      int index = mainMeal(entry.section);
      if (index >= 0) consumed[index] += entry.calories;
      else if (entry.section.equals("Перекусы")) {
        approximate |= entry.calories > 0;
        // Untimed legacy snacks are filled chronologically, never guessed as a future snack.
        double rest = entry.calories;
        int last = -1;
        for (int i = 0; i < SLOTS.size(); i++) {
          Slot slot = SLOTS.get(i);
          if (!slot.section.equals("Перекусы") || slot.start.isAfter(time)) continue;
          last = i;
          double take = Math.min(rest, Math.max(0, slot.calories - consumed[i]));
          consumed[i] += take;
          rest -= take;
        }
        if (last >= 0) consumed[last] += rest;
        else unassigned += rest;
      } else {
        unassigned += entry.calories;
        approximate |= entry.calories > 0;
      }
    }
    double remaining = Math.max(0, DAILY - total);
    int first = 0;
    while (first < SLOTS.size() && !time.isBefore(SLOTS.get(first).end)) first++;
    int charge = Math.min(first, SLOTS.size() - 1);
    if (first < SLOTS.size() && time.isBefore(SLOTS.get(first).start) && first > 0)
      charge = first - 1;
    consumed[charge] += unassigned;
    if (first == SLOTS.size() || remaining < 1) {
      Slot tomorrow = SLOTS.get(0);
      return new Guidance(
          null,
          0,
          tomorrow,
          tomorrow.calories,
          date.plusDays(1).atTime(tomorrow.start),
          remaining,
          approximate,
          true);
    }
    double[] desired = new double[SLOTS.size() - first];
    for (int i = first; i < SLOTS.size(); i++)
      desired[i - first] = SLOTS.get(i).calories - consumed[i];
    int[] budgets = distribute(desired, (int) Math.floor(remaining + 1e-8));
    Slot active = null;
    int allowed = 0;
    if (!time.isBefore(SLOTS.get(first).start)) {
      active = SLOTS.get(first);
      allowed = budgets[0];
    }
    int next = first;
    if (active != null) next++;
    while (next < SLOTS.size() && budgets[next - first] <= 0) next++;
    Slot upcoming = next < SLOTS.size() ? SLOTS.get(next) : SLOTS.get(0);
    LocalDateTime nextAt =
        next < SLOTS.size() ? date.atTime(upcoming.start) : date.plusDays(1).atTime(upcoming.start);
    int nextKcal = next < SLOTS.size() ? budgets[next - first] : upcoming.calories;
    return new Guidance(active, allowed, upcoming, nextKcal, nextAt, remaining, approximate, false);
  }

  private static int mainMeal(String section) {
    if (section.equals("Перекусы") || section.equals("Заметки")) return -1;
    for (int i = 0; i < SLOTS.size(); i++) if (SLOTS.get(i).section.equals(section)) return i;
    return -1;
  }

  /** Equal absolute carry, with iterative zero-flooring, then deterministic integer rounding. */
  static int[] distribute(double[] desired, int remaining) {
    int[] result = new int[desired.length];
    if (desired.length == 0 || remaining <= 0) return result;
    boolean[] included = new boolean[desired.length];
    Arrays.fill(included, true);
    int count = desired.length;
    double shift = 0;
    while (count > 0) {
      double sum = 0;
      for (int i = 0; i < desired.length; i++) if (included[i]) sum += desired[i];
      shift = (remaining - sum) / count;
      boolean removed = false;
      for (int i = 0; i < desired.length; i++)
        if (included[i] && desired[i] + shift < 0) {
          included[i] = false;
          count--;
          removed = true;
        }
      if (!removed) break;
    }
    double[] fractions = new double[desired.length];
    int assigned = 0;
    for (int i = 0; i < desired.length; i++)
      if (included[i]) {
        double value = Math.max(0, desired[i] + shift);
        result[i] = (int) Math.floor(value);
        fractions[i] = value - result[i];
        assigned += result[i];
      }
    while (assigned < remaining) {
      int best = -1;
      for (int i = 0; i < desired.length; i++)
        if (included[i] && (best < 0 || fractions[i] > fractions[best])) best = i;
      if (best < 0) break;
      result[best]++;
      fractions[best] = -1;
      assigned++;
    }
    return result;
  }

  private static final java.time.format.DateTimeFormatter HHMM =
      java.time.format.DateTimeFormatter.ofPattern("HH:mm");

  public static String hint(LocalDate date, LocalDateTime now, VaultMarkdown.Day day) {
    if (!date.equals(now.toLocalDate())) return "";
    var entries = day.entries.stream().map(e -> new Eaten(e.section, e.kcal)).toList();
    var advice = guidance(date, now, entries);
    String nextName = advice.next.name;
    String at = advice.next.start.format(HHMM);
    String tomorrow = advice.nextAt.toLocalDate().isAfter(date) ? "завтра, " : "";
    long millis = Math.max(0, Duration.between(now, advice.nextAt).toMillis());
    long seconds = (millis + 999) / 1000;
    String wait =
        String.format(
            Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
    String next = nextName + ": ≈ " + advice.nextCalories + " ккал · " + tomorrow + at;
    if (advice.now != null && advice.available > 0)
      return advice.now.name
          + ": ещё ≈ "
          + advice.available
          + " ккал до "
          + advice.now.end.format(HHMM)
          + "\nДалее — "
          + next;
    String status =
        advice.dailyRemaining < 1
            ? "Дневной ориентир набран"
            : advice.ended ? "На сегодня приёмы по плану завершены" : "Сейчас по плану: 0 ккал";
    return status + "\n" + next + " · через " + wait;
  }

  private MealPlan() {}
}
