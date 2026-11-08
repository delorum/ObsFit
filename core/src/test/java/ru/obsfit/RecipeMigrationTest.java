package ru.obsfit;

import static org.junit.Assert.*;

import java.time.LocalDate;
import org.junit.Test;

public class RecipeMigrationTest {
  private final VaultMarkdown.Catalog catalog = Fixtures.catalog().catalog;
  private static final LocalDate DATE = LocalDate.of(2026, 11, 1);

  private String mark(String source) {
    return RecipeMigration.mark(DATE, source, catalog);
  }

  @Test
  public void neverChangesDatesBeforeNovemberAndDoesNotRelabelMarkedBlocks() {
    String source = "### Обед\nхл 40 г 80 ккал\nсыр 20 г 60 ккал\n";
    assertEquals(source, RecipeMigration.mark(DATE.minusDays(1), source, catalog));
    String once = mark(source);
    assertTrue(once.contains("бутерброд:\n  - хл 40 г 80 ккал\n  - сыр 20 г 60 ккал"));
    assertEquals(once, mark(once));
    assertEquals(140, VaultMarkdown.day(once, catalog).total, 0);
  }

  @Test
  public void expandsLegacyExplicitRecipesWhilePreservingRecordedCalories() {
    String source = "### Обед\nббр 30 г 75 ккал\nСъеденоКалорий:: 75\n";
    String marked = mark(source);
    var group = VaultMarkdown.day(marked, catalog).recipes.get(0);
    assertEquals(30, group.grams(), 0);
    assertEquals(75, group.entries().stream().mapToDouble(e -> e.recordedKcal).sum(), 0);
    assertTrue(marked.contains("СъеденоКалорий:: 75"));
    assertEquals(marked, mark(marked));
  }

  @Test
  public void permitsBlankLinesButNeverMovesFoodAcrossOtherTextMealsOrCode() {
    assertTrue(mark("### Обед\nхл 40\n\nсыр 20\n").contains("бутерброд:"));
    for (String separator :
        new String[] {"заметка", "### Ужин", "другое 100 ккал", "```\nтекст\n```"}) {
      String source = "### Обед\nхл 40\n" + separator + "\nсыр 20\n";
      assertEquals(source, mark(source));
    }
  }

  @Test
  public void choosesLargestRecipeBeforeItsSubsetsAndClosestProportionsForIdenticalSets() {
    var choices =
        VaultMarkdown.catalog(
            Fixtures.FOOD.replace(
                "<!-- food-table:end -->",
                "| другой бутерброд | хл 10 г<br>сыр 50 г | | |\n"
                    + "| вода | | 0 | 100 |\n"
                    + "| набор | ббр 60 г<br>вода 100 г | | |\n"
                    + "<!-- food-table:end -->"));
    var first = RecipeMigration.mark(DATE, "### Обед\nхл 10\nсыр 50\n", choices);
    assertTrue(first.contains("другой бутерброд:"));
    assertEquals(1, VaultMarkdown.day(first, choices).recipes.size());
    var large = RecipeMigration.mark(DATE, "### Обед\nхл 40\nсыр 20\nвода 100\n", choices);
    assertTrue(large.contains("набор:"));
    assertEquals(1, VaultMarkdown.day(large, choices).recipes.size());
  }

  @Test
  public void preservesCrLfAndUnrelatedTextAndDataviewFields() {
    String source =
        "# День\r\nТекст\r\n### Обед\r\nхл 40 г 80 ккал\r\nсыр 20 г 60 ккал\r\nВес:: 103\r\n";
    String result = mark(source);
    assertFalse(result.replace("\r\n", "").contains("\n"));
    assertTrue(result.startsWith("# День\r\nТекст\r\n"));
    assertTrue(result.endsWith("Вес:: 103\r\n"));
  }
}
