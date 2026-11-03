package ru.obsfit;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class DailyCalorieLimitTest {
  private static final LocalDate DATE = LocalDate.of(2026, 11, 3);

  private String note(String value) {
    return "## Питание\n### Завтрак\n100 ккал\n### Спорт и диета\nСуточнаяНормаКалорий:: "
        + value
        + "\n";
  }

  @Test
  public void readsDailyFieldWithoutChangingCaloriesOrSource() {
    var day = VaultMarkdown.day(note("2300"), Fixtures.catalog().catalog);
    assertEquals(2300, day.dailyLimit, 0);
    assertEquals(100, day.total, 0);
    assertEquals(note("2300"), day.source);
    assertEquals(2175.5, VaultMarkdown.dailyLimit(note("2175,5")), 0);
    assertEquals(2600, VaultMarkdown.dailyLimit(note("2600").replace("\n", "\r\n")), 0);
  }

  @Test
  public void missingInvalidAndDuplicateFieldsUse2150() {
    assertEquals(2150, VaultMarkdown.dailyLimit(Fixtures.DAY), 0);
    for (String invalid :
        List.of("", "0", "-1", "NaN", "Infinity", "не число", "2150 ккал", "1000000001"))
      assertEquals(invalid, 2150, VaultMarkdown.dailyLimit(note(invalid)), 0);
    assertEquals(2150, VaultMarkdown.dailyLimit(note("2200") + "СуточнаяНормаКалорий:: 2300\n"), 0);
  }

  @Test
  public void ignoresExamplesOutsideSportsCodeCommentsQuotesAndFrontmatter() {
    String misleading =
        "---\nСуточнаяНормаКалорий:: 900\n---\n"
            + "## Заметки\nСуточнаяНормаКалорий:: 900\n"
            + "### Спорт и диета\n```\nСуточнаяНормаКалорий:: 900\n```\n"
            + "<!--\nСуточнаяНормаКалорий:: 900\n-->\n"
            + "%%\nСуточнаяНормаКалорий:: 900\n%%\n> СуточнаяНормаКалорий:: 900\n"
            + "СуточнаяНормаКалорий:: 2400\n### Другое\nСуточнаяНормаКалорий:: 900\n";
    assertEquals(2400, VaultMarkdown.dailyLimit(misleading), 0);
    assertEquals(2150, VaultMarkdown.dailyLimit("## Заметки\nСуточнаяНормаКалорий:: 900"), 0);
  }

  @Test
  public void editingFoodPreservesDailyLimitField() {
    String source = note("2350");
    var catalog = Fixtures.catalog().catalog;
    String updated = VaultMarkdown.add(source, "Обед", "50 ккал", catalog);
    assertEquals(2350, VaultMarkdown.day(updated, catalog).dailyLimit, 0);
    assertTrue(updated.contains("СуточнаяНормаКалорий:: 2350\n"));
  }

  @Test
  public void guidanceUsesDefaultOrExplicitLimitAndScalesMealProportions() {
    var standard = MealPlan.guidance(DATE, DATE.atTime(9, 0), List.of());
    assertEquals(2150, standard.dailyRemaining(), 0);
    assertEquals(500 * 2150d / 2600, standard.available(), 1);
    var historical = MealPlan.guidance(DATE, DATE.atTime(9, 0), List.of(), 2600);
    assertEquals(500, historical.available());
    var reached =
        MealPlan.guidance(
            DATE, DATE.atTime(13, 0), List.of(new MealPlan.Eaten("Обед", 2200)), 2200);
    assertTrue(reached.ended());
    assertEquals(0, reached.dailyRemaining(), 0);
    assertEquals(0, reached.available());
  }

  @Test
  public void allocatingEveryMealConservesTheSelectedDailyLimit() {
    for (int limit : new int[] {1800, 2150, 2200, 2600, 2800}) {
      List<MealPlan.Eaten> consumed = new ArrayList<>();
      int total = 0;
      for (var slot : MealPlan.SLOTS) {
        var advice = MealPlan.guidance(DATE, DATE.atTime(slot.start()), consumed, limit);
        assertTrue(advice.available() >= 0);
        total += advice.available();
        consumed.add(new MealPlan.Eaten(slot.section(), advice.available()));
      }
      assertEquals("limit " + limit, limit, total);
    }
  }

  @Test
  public void hintReadsNoteLimitAndDoesNotModifySource() {
    var day = VaultMarkdown.day(note("1000"), Fixtures.catalog().catalog);
    assertTrue(MealPlan.hint(DATE, DATE.atTime(9, 0), day).contains("ещё ≈ 92 ккал"));
    assertEquals(note("1000"), day.source);
    for (double invalid : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
      assertEquals(
          2150, MealPlan.guidance(DATE, DATE.atTime(9, 0), List.of(), invalid).dailyRemaining(), 0);
  }
}
