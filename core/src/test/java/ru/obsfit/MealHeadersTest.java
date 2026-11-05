package ru.obsfit;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class MealHeadersTest {
  private static final LocalDate DATE = LocalDate.of(2026, 11, 5);

  private static VaultMarkdown.Day day(double limit, MealPlan.Eaten... entries) {
    StringBuilder text = new StringBuilder();
    for (var entry : entries)
      text.append("## ")
          .append(entry.section())
          .append("\n")
          .append(VaultMarkdown.fmt(entry.calories()))
          .append(" ккал\n");
    text.append("## Спорт и диета\nСуточнаяНормаКалорий:: ")
        .append(VaultMarkdown.fmt(limit))
        .append("\n");
    return VaultMarkdown.day(text.toString(), Fixtures.catalog().catalog);
  }

  private static MealPlan.Eaten food(String name, double kcal) {
    return new MealPlan.Eaten(name, kcal);
  }

  private static Map<String, MealPlan.SectionBudget> at(String time, VaultMarkdown.Day day) {
    return MealPlan.sectionBudgets(DATE, DATE.atTime(LocalTime.parse(time)), day);
  }

  @Test
  public void emptyDayShowsProportionalBudgetsWithoutExtraNotesSlot() {
    var map = at("08:00", day(2600));
    assertEquals(8, map.size());
    assertFalse(map.containsKey("Заметки"));
    for (var slot : MealPlan.SLOTS)
      assertEquals(slot.calories(), map.get(slot.section()).remaining());
    assertEquals("≈500 ккал", map.get("Завтрак").label());
    assertEquals(
        2150,
        at("08:00", day(2150)).values().stream().mapToInt(MealPlan.SectionBudget::remaining).sum());
  }

  @Test
  public void currentMealShowsEatenAndSignedDifference() {
    assertEquals(
        "350 ккал\nещё ≈150", at("09:30", day(2600, food("Завтрак", 350))).get("Завтрак").label());
    assertEquals(
        "500 ккал\nпо плану", at("09:30", day(2600, food("Завтрак", 500))).get("Завтрак").label());
    assertEquals(
        "600 ккал\nсверх ≈100",
        at("09:30", day(2600, food("Завтрак", 600))).get("Завтрак").label());
  }

  @Test
  public void closedMealShortfallIsAlreadyCarriedSoDoesNotInviteEatingTwice() {
    var d = day(2600, food("Завтрак", 350));
    var before = at("09:59", d);
    var after = at("10:00", d);
    assertEquals("350 ккал\nещё ≈150", before.get("Завтрак").label());
    assertEquals("350 ккал\nнедобор ≈150", after.get("Завтрак").label());
    int future =
        after.entrySet().stream()
            .filter(e -> !e.getKey().equals("Завтрак"))
            .mapToInt(e -> e.getValue().remaining())
            .sum();
    assertEquals(2250, future);
    assertEquals(0, at("11:00", day(2600)).get("Завтрак").remaining());
    assertTrue(at("11:00", day(2600)).get("Перекус 1").remaining() > 225);
  }

  @Test
  public void overrunReducesLaterMealsAndRemainsVisibleAfterClosing() {
    var d = day(2600, food("Завтрак", 700));
    var map = at("11:00", d);
    assertEquals(-200, map.get("Завтрак").remaining());
    assertTrue(map.get("Перекус 1").remaining() < 225);
    assertEquals(
        1900,
        map.entrySet().stream()
            .filter(e -> !e.getKey().equals("Завтрак"))
            .mapToInt(e -> e.getValue().remaining())
            .sum());
  }

  @Test
  public void futureFilledMealsAndNotesFollowSameAllowanceAsMainHint() {
    for (var entries :
        List.of(
            List.of(food("Заметки", 300)),
            List.of(food("Завтрак", 700), food("Ужин", 500)),
            List.of(food("Заметки", 300), food("Перекус 1", 200)))) {
      var d = day(2600, entries.toArray(MealPlan.Eaten[]::new));
      var expected = MealPlan.guidance(DATE, DATE.atTime(11, 30), entries, 2600);
      assertEquals(expected.available(), Math.max(0, at("11:30", d).get("Перекус 1").remaining()));
    }
  }

  @Test
  public void reachingDayLimitDoesNotOfferMoreAndKeepsOverrun() {
    var map = at("09:30", day(2600, food("Завтрак", 3000)));
    assertEquals(-2500, map.get("Завтрак").remaining());
    for (var slot : MealPlan.SLOTS)
      if (!slot.section().equals("Завтрак")) assertEquals(0, map.get(slot.section()).remaining());
  }

  @Test
  public void historicDaysAreClosedFutureDaysAreNotAffectedByTodaysClock() {
    var now = DATE.atTime(13, 30);
    var d = day(2600, food("Завтрак", 350));
    assertTrue(
        MealPlan.sectionBudgets(DATE.minusDays(1), now, d).values().stream()
            .allMatch(MealPlan.SectionBudget::closed));
    assertFalse(
        MealPlan.sectionBudgets(DATE.plusDays(1), now, d).values().stream()
            .anyMatch(MealPlan.SectionBudget::closed));
  }

  @Test
  public void closedBreakfastDoesNotChangeBecauseOfFoodAddedToDinner() {
    assertEquals(
        at("19:30", day(2600, food("Завтрак", 400))).get("Завтрак"),
        at("19:30", day(2600, food("Завтрак", 400), food("Ужин", 600))).get("Завтрак"));
  }

  @Test
  public void randomizedHeadersAgreeWithGuidanceAcrossAllWindows() {
    var rng = new Random(19);
    for (int trial = 0; trial < 100; trial++) {
      double limit = 1800 + rng.nextInt(900);
      List<MealPlan.Eaten> entries = new ArrayList<>();
      for (var slot : MealPlan.SLOTS) entries.add(food(slot.section(), rng.nextInt(650)));
      var d = day(limit, entries.toArray(MealPlan.Eaten[]::new));
      for (var slot : MealPlan.SLOTS) {
        var clock = DATE.atTime(slot.start().plusMinutes(15));
        var advice = MealPlan.guidance(DATE, clock, entries, limit);
        var budget = MealPlan.sectionBudgets(DATE, clock, d).get(slot.section());
        assertEquals(advice.available(), Math.max(0, budget.remaining()));
      }
    }
  }
}
