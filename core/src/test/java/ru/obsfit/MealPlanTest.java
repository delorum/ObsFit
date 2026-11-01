package ru.obsfit;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class MealPlanTest {
  private static final LocalDate DAY = LocalDate.of(2026, 11, 1);

  private MealPlan.Eaten eaten(String section, double calories) {
    return new MealPlan.Eaten(section, calories);
  }

  private MealPlan.Guidance at(String time, MealPlan.Eaten... food) {
    return MealPlan.guidance(DAY, DAY.atTime(LocalTime.parse(time)), List.of(food));
  }

  @Test
  public void scheduleMatchesEightWindowsAnd2600Calories() {
    assertEquals(8, MealPlan.SLOTS.size());
    assertEquals(
        List.of(500, 225, 400, 225, 400, 225, 400, 225),
        MealPlan.SLOTS.stream().map(MealPlan.Slot::calories).toList());
    assertEquals(2600, MealPlan.SLOTS.stream().mapToInt(MealPlan.Slot::calories).sum());
    assertEquals(
        List.of("09:00", "11:00", "13:00", "15:00", "17:00", "18:00", "19:00", "21:00"),
        MealPlan.SLOTS.stream().map(s -> s.start().toString()).toList());
    assertEquals(
        List.of("10:00", "12:00", "14:00", "16:00", "18:00", "19:00", "20:00", "22:00"),
        MealPlan.SLOTS.stream().map(s -> s.end().toString()).toList());
    assertEquals(4, MealPlan.SLOTS.stream().filter(s -> s.calories() == 225).count());
  }

  @Test
  public void beforeBreakfastWaitsForNineWithoutReadingFiles() {
    var a = at("08:00");
    assertNull(a.now());
    assertEquals(0, a.available());
    assertEquals(500, a.nextCalories());
    assertEquals(DAY.atTime(9, 0), a.nextAt());
  }

  @Test
  public void openingBoundaryIsIncludedAndClosingBoundaryIsExcluded() {
    assertEquals(500, at("09:00").available());
    assertEquals(500, at("09:59:59").available());
    assertNull(at("10:00").now());
    assertEquals(DAY.atTime(11, 0), at("10:00").nextAt());
  }

  @Test
  public void currentMealConsumptionIsSubtractedImmediately() {
    var a = at("09:30", eaten("Завтрак", 350));
    assertEquals(150, a.available());
    assertEquals("Завтрак", a.now().name());
  }

  @Test
  public void fullCurrentMealShowsNextMealInsteadOfMoreCalories() {
    var a = at("09:30", eaten("Завтрак", 500));
    assertEquals(0, a.available());
    assertEquals(DAY.atTime(11, 0), a.nextAt());
    assertEquals(225, a.nextCalories());
  }

  @Test
  public void missedBreakfastIsCarriedEquallyToSevenRemainingMeals() {
    var a = at("11:00");
    assertEquals(225 + 500d / 7, a.available(), 1);
    assertEquals(400 + 500d / 7, a.nextCalories(), 1);
  }

  @Test
  public void breakfastOverspendReducesAllRemainingMealsEqually() {
    var a = at("11:00", eaten("Завтрак", 650));
    assertEquals(225 - 150d / 7, a.available(), 1);
    assertEquals(400 - 150d / 7, a.nextCalories(), 1);
  }

  @Test
  public void exhaustedCurrentBudgetIsZeroAndOverspendMovesForward() {
    var a = at("09:30", eaten("Завтрак", 800));
    assertEquals(0, a.available());
    assertEquals(225 - 300d / 7, a.nextCalories(), 1);
  }

  @Test
  public void canSkipAFutureMealWhoseRemainingBudgetIsAlreadyZero() {
    var a = at("09:30", eaten("Завтрак", 2450));
    assertEquals(0, a.available());
    assertEquals("Обед", a.next().name());
    assertEquals(DAY.atTime(13, 0), a.nextAt());
  }

  @Test
  public void filledFutureMainSectionIsNotAllocatedTwice() {
    var a = at("11:00", eaten("Завтрак", 500), eaten("Обед", 400));
    assertEquals(225, a.available());
    assertEquals(DAY.atTime(15, 0), a.nextAt());
  }

  @Test
  public void numberedSnacksAreAssignedToTheirOwnWindows() {
    var a = at("15:30", eaten("Завтрак", 500), eaten("Обед", 400), eaten("Перекус 1", 225));
    assertEquals(225, a.available());
    assertFalse(a.approximate());
    var b =
        at(
            "15:30",
            eaten("Завтрак", 500),
            eaten("Обед", 400),
            eaten("Перекус 1", 225),
            eaten("Перекус 2", 75));
    assertEquals(150, b.available());
  }

  @Test
  public void notesCountTowardCurrentMealAndAreMarkedApproximate() {
    var a = at("09:30", eaten("Заметки", 350));
    assertEquals(150, a.available());
    assertTrue(a.approximate());
    var b = at("10:30", eaten("Заметки", 500));
    assertEquals(225, b.nextCalories());
  }

  @Test
  public void reachingDailyGoalDoesNotCreateNegativeBudgets() {
    var a = at("13:00", eaten("Обед", 2700));
    assertEquals(0, a.available());
    assertEquals(0, a.dailyRemaining(), 0);
    assertTrue(a.ended());
    assertEquals(DAY.plusDays(1).atTime(9, 0), a.nextAt());
  }

  @Test
  public void finalSnackReceivesRemainingBudgetAndEndIsHandled() {
    var a =
        at(
            "21:30",
            eaten("Завтрак", 500),
            eaten("Обед", 400),
            eaten("Второй обед", 400),
            eaten("Ужин", 400),
            eaten("Перекус 1", 225),
            eaten("Перекус 2", 225),
            eaten("Перекус 3", 225));
    assertEquals(225, a.available());
    var b = at("22:00");
    assertEquals(0, b.available());
    assertTrue(b.ended());
    assertEquals(DAY.plusDays(1).atTime(9, 0), b.nextAt());
  }

  @Test
  public void roundingPreservesWholeCalorieDailyBudget() {
    int[] values = MealPlan.distribute(new double[] {300, 400, 300, 400, 400, 300}, 2000);
    assertEquals(2000, Arrays.stream(values).sum());
    assertTrue(Arrays.stream(values).allMatch(n -> n >= 0));
    assertTrue(Math.abs((values[0] - 300) - (values[1] - 400)) <= 1);
  }

  @Test
  public void waterFillingConservesRemainingCaloriesAcrossRandomOverruns() {
    Random random = new Random(42);
    for (int trial = 0; trial < 1000; trial++) {
      double[] desired = new double[1 + random.nextInt(7)];
      for (int i = 0; i < desired.length; i++) desired[i] = random.nextInt(2000) - 1000;
      int remaining = random.nextInt(2601);
      int[] allocated = MealPlan.distribute(desired, remaining);
      assertEquals(remaining, Arrays.stream(allocated).sum());
      assertTrue(Arrays.stream(allocated).allMatch(n -> n >= 0));
    }
  }

  @Test
  public void countdownUpdatesFromClockAndDoesNotChangeDiaryText() {
    var day = Fixtures.day();
    String first = MealPlan.hint(DAY, DAY.atTime(8, 59, 50), day.day);
    String second = MealPlan.hint(DAY, DAY.atTime(8, 59, 51), day.day);
    assertTrue(first.contains("00:00:10"));
    assertTrue(second.contains("00:00:09"));
    assertEquals(Fixtures.DAY, day.day.source);
  }

  @Test
  public void lastFractionOfASecondStillShowsOneSecondRemaining() {
    assertTrue(
        MealPlan.hint(DAY, DAY.atTime(8, 59, 59, 500_000_000), Fixtures.day().day)
            .contains("00:00:01"));
  }

  @Test
  public void guidanceIsOnlyShownForTodaysSelectedNote() {
    var day = Fixtures.day();
    assertEquals("", MealPlan.hint(DAY.minusDays(1), DAY.atTime(9, 0), day.day));
    assertEquals("", MealPlan.hint(DAY.plusDays(1), DAY.atTime(9, 0), day.day));
  }

  @Test
  public void malformedCaloriesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> at("09:30", eaten("Обед", Double.NaN)));
    assertThrows(IllegalArgumentException.class, () -> at("09:30", eaten("Обед", -10)));
  }
}
