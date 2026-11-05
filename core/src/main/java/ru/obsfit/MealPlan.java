package ru.obsfit;

import java.time.*;
import java.util.*;

/** Daily budget for the user’s fixed schedule. No Android or I/O. */
public final class MealPlan {
  public static final int DAILY = VaultMarkdown.DEFAULT_DAILY_CALORIES;
  private static final int BASE_DAILY = 2600;

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
          slot("Завтрак", "Завтрак", "09:00", "10:00", 500),
          slot("Перекус 1", "Перекус 1", "11:00", "12:00", 225),
          slot("Обед", "Обед", "13:00", "14:00", 400),
          slot("Перекус 2", "Перекус 2", "15:00", "16:00", 225),
          slot("Второй обед", "Второй обед", "17:00", "18:00", 400),
          slot("Перекус 3", "Перекус 3", "18:00", "19:00", 225),
          slot("Ужин", "Ужин", "19:00", "20:00", 400),
          slot("Перекус 4", "Перекус 4", "21:00", "22:00", 225));

  private static Slot slot(String name, String section, String from, String to, int kcal) {
    return new Slot(name, section, LocalTime.parse(from), LocalTime.parse(to), kcal);
  }

  public static Guidance guidance(LocalDate date, LocalDateTime clock, List<Eaten> entries) {
    return guidance(date, clock, entries, DAILY);
  }

  public static Guidance guidance(
      LocalDate date, LocalDateTime clock, List<Eaten> entries, double dailyLimit) {
    dailyLimit = validLimit(dailyLimit);
    double scale = dailyLimit / BASE_DAILY;
    LocalTime time = clock.toLocalTime();
    var allocation = allocate(entries, dailyLimit, time);
    int first = allocation.first;
    double remaining = allocation.remaining;
    boolean approximate = allocation.approximate;
    if (first == SLOTS.size() || remaining < 1) {
      Slot tomorrow = SLOTS.get(0);
      return new Guidance(
          null,
          0,
          tomorrow,
          (int) Math.round(tomorrow.calories * scale),
          date.plusDays(1).atTime(tomorrow.start),
          remaining,
          approximate,
          true);
    }
    int[] budgets = allocation.budgets;
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
    int nextKcal =
        next < SLOTS.size() ? budgets[next - first] : (int) Math.round(upcoming.calories * scale);
    return new Guidance(active, allowed, upcoming, nextKcal, nextAt, remaining, approximate, false);
  }

  private record Allocation(
      int first, double[] consumed, double remaining, boolean approximate, int[] budgets) {}

  private static double validLimit(double limit) {
    return Double.isFinite(limit) && limit > 0 && limit <= 1_000_000_000 ? limit : DAILY;
  }

  private static int[] budgets(double[] consumed, int first, double limit, double remaining) {
    double[] desired = new double[SLOTS.size() - first];
    for (int i = first; i < SLOTS.size(); i++)
      desired[i - first] = SLOTS.get(i).calories * limit / BASE_DAILY - consumed[i];
    return distribute(desired, (int) Math.floor(Math.max(0, remaining) + 1e-8));
  }

  private static Allocation allocate(List<Eaten> entries, double dailyLimit, LocalTime time) {
    double[] consumed = new double[SLOTS.size()];
    double total = 0, unassigned = 0;
    boolean approximate = false;
    for (Eaten entry : entries) {
      if (!Double.isFinite(entry.calories) || entry.calories < 0)
        throw new IllegalArgumentException("Invalid calories");
      total += entry.calories;
      int index = mainMeal(entry.section);
      if (index >= 0) consumed[index] += entry.calories;
      else {
        unassigned += entry.calories;
        approximate |= entry.calories > 0;
      }
    }
    double remaining = Math.max(0, dailyLimit - total);
    int first = 0;
    while (first < SLOTS.size() && !time.isBefore(SLOTS.get(first).end)) first++;
    int charge = Math.min(first, SLOTS.size() - 1);
    if (first < SLOTS.size() && time.isBefore(SLOTS.get(first).start) && first > 0)
      charge = first - 1;
    consumed[charge] += unassigned;
    return new Allocation(
        first, consumed, remaining, approximate, budgets(consumed, first, dailyLimit, remaining));
  }

  public record SectionBudget(double eaten, int remaining, boolean closed) {
    public String label() {
      if (eaten == 0) return "≈" + Math.max(0, remaining) + " ккал";
      String difference =
          remaining < 0
              ? "сверх ≈" + -remaining
              : remaining > 0 ? (closed ? "недобор ≈" : "ещё ≈") + remaining : "по плану";
      return VaultMarkdown.fmt(eaten) + " ккал\n" + difference;
    }
  }

  /** Same allocation as guidance; closed meals keep their own before-meal comparison. */
  public static Map<String, SectionBudget> sectionBudgets(
      LocalDate date, LocalDateTime now, VaultMarkdown.Day day) {
    LocalTime time =
        date.isBefore(now.toLocalDate())
            ? LocalTime.MAX
            : date.isAfter(now.toLocalDate()) ? LocalTime.MIN : now.toLocalTime();
    double limit = validLimit(day.dailyLimit);
    var entries = day.entries.stream().map(e -> new Eaten(e.section, e.kcal)).toList();
    var allocation = allocate(entries, limit, time);
    Map<String, SectionBudget> result = new LinkedHashMap<>();
    for (int i = 0; i < SLOTS.size(); i++) {
      String section = SLOTS.get(i).section;
      double eaten = day.section(section).stream().mapToDouble(e -> e.kcal).sum();
      int balance;
      boolean closed = i < allocation.first;
      if (closed) {
        // Only earlier meals affect the allowance at the start of this closed meal.
        double[] before = allocation.consumed.clone();
        Arrays.fill(before, i, before.length, 0);
        double available = limit - Arrays.stream(before).sum();
        int allowance = budgets(before, i, limit, available)[0];
        balance = eaten == 0 ? 0 : (int) Math.round(allowance - allocation.consumed[i]);
      } else {
        balance = allocation.budgets[i - allocation.first];
        if (allocation.consumed[i] > 0) {
          // Reconstruct its allowance without this meal to preserve a signed overrun.
          double[] without = allocation.consumed.clone();
          without[i] = 0;
          double available = limit - Arrays.stream(without).sum();
          int allowance =
              budgets(without, allocation.first, limit, available)[i - allocation.first];
          int delta = (int) Math.round(allowance - allocation.consumed[i]);
          if (delta < 0) balance = delta;
        }
      }
      result.put(section, new SectionBudget(eaten, balance, closed));
    }
    return Collections.unmodifiableMap(result);
  }

  private static int mainMeal(String section) {
    section = VaultMarkdown.sectionName(section);
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
    var advice = guidance(date, now, entries, day.dailyLimit);
    String nextName = advice.next.name;
    String at = advice.next.start.format(HHMM);
    String tomorrow = advice.nextAt.toLocalDate().isAfter(date) ? "завтра, " : "";
    long millis = Math.max(0, Duration.between(now, advice.nextAt).toMillis());
    long minutes = (millis + 59_999) / 60_000;
    String wait = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
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
