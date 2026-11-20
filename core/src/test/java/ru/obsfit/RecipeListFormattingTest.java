package ru.obsfit;

import static org.junit.Assert.*;

import java.util.*;
import java.util.stream.Collectors;
import org.junit.Test;

public class RecipeListFormattingTest {
  private final VaultMarkdown.Catalog catalog = VaultMarkdown.catalog(Fixtures.FOOD);
  private static final String SOURCE =
      "### Завтрак\n"
          + "бутерброд:\n"
          + "  - хлеб 40 г 80 ккал\n"
          + "  - сыр 20 г 60 ккал\n"
          + "сыр 10 г 30 ккал\n\n"
          + "### Спорт и диета\n"
          + "СъеденоКалорий:: 170\n";

  private RecipeBlocks.Group group(String source, String name) {
    return VaultMarkdown.day(source, catalog).recipes.stream()
        .filter(g -> g.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private VaultMarkdown.Entry outside(String source) {
    return VaultMarkdown.day(source, catalog).entries.stream()
        .filter(e -> e.raw.equals("сыр 10 г 30 ккал"))
        .findFirst()
        .orElseThrow();
  }

  // The vault formatter trims non-list prose; a nested food row must therefore carry its marker.
  private String trimProse(String source) {
    return source
        .lines()
        .map(
            line ->
                line.stripLeading().startsWith("-") || line.stripLeading().startsWith("*")
                    ? line.stripTrailing()
                    : line.strip())
        .collect(Collectors.joining("\n"));
  }

  private void survivesFormatting(String source, int groups, double calories) {
    String formatted = trimProse(source);
    assertEquals(groups, VaultMarkdown.day(formatted, catalog).recipes.size());
    assertEquals(calories, VaultMarkdown.day(formatted, catalog).total, 0);
  }

  @Test
  public void plainFoodDroppedAtRecipeStartGetsTwoSpaceDashPrefix() {
    String result =
        RecipeBlocks.reparent(
            SOURCE, RecipeBlocks.piece(outside(SOURCE)), group(SOURCE, "бутерброд"), null, catalog);
    assertTrue(result.contains("бутерброд:\n  - сыр 10 г 30 ккал\n"));
    assertEquals(3, group(result, "бутерброд").entries().size());
    survivesFormatting(result, 1, 170);
  }

  @Test
  public void plainFoodDroppedAfterIngredientGetsSameListStyle() {
    var target = group(SOURCE, "бутерброд");
    String result =
        RecipeBlocks.reparent(
            SOURCE,
            RecipeBlocks.piece(outside(SOURCE)),
            target,
            RecipeBlocks.piece(target.entries().get(0)),
            catalog);
    assertTrue(
        result.contains("  - хлеб 40 г 80 ккал\n  - сыр 10 г 30 ккал\n  - сыр 20 г 60 ккал"));
    survivesFormatting(result, 1, 170);
  }

  @Test
  public void movingAnUnbulletedCompositeInsideAnotherAddsHeaderAndLeafMarkers() {
    String source = SOURCE.replace("сыр 10 г 30 ккал", "Другой набор:\n  сыр 10 г 30 ккал");
    String result =
        RecipeBlocks.reparent(
            source,
            RecipeBlocks.piece(group(source, "Другой набор")),
            group(source, "бутерброд"),
            null,
            catalog);
    assertTrue(result.contains("бутерброд:\n  - Другой набор:\n    - сыр 10 г 30 ккал"));
    assertEquals(group(result, "бутерброд").line(), group(result, "Другой набор").parentLine());
    survivesFormatting(result, 2, 170);
  }

  @Test
  public void editingNestedRecipeNameKeepsListHeaderAndAllChildIndentation() {
    String source =
        "### Завтрак\n"
            + "Набор:\n"
            + "  - бутерброд:\n"
            + "    - хлеб 40 г 80 ккал\n"
            + "    - сыр 20 г 60 ккал\n"
            + "сыр 10 г 30 ккал\n";
    var form = new DiaryRecipeForm(Fixtures.day(source, Fixtures.FOOD), group(source, "бутерброд"));
    form.name = "Изменённое блюдо";
    String result = form.save();
    assertTrue(result.contains("Набор:\n  - Изменённое блюдо:\n    - хлеб"));
    survivesFormatting(result, 2, 170);
  }

  @Test
  public void editingRootRecipeNormalizesPreviouslyUnbulletedChildrenOnlyWithinThatBlock() {
    String source = SOURCE.replace("  - хлеб", "  хлеб").replace("  - сыр", "  сыр");
    var form = new DiaryRecipeForm(Fixtures.day(source, Fixtures.FOOD), group(source, "бутерброд"));
    String result = form.save();
    assertTrue(result.contains("бутерброд:\n  - хлеб 40 г 80 ккал\n  - сыр 20 г 60 ккал"));
    assertTrue(result.contains("\nсыр 10 г 30 ккал\n"));
    survivesFormatting(result, 1, 170);
  }

  @Test
  public void markerNormalizationDoesNotDuplicateExistingMarkersOrChangeManualCalories() {
    String source = SOURCE.replace("сыр 10 г 30 ккал", "перекус 30 ккал");
    var entry =
        VaultMarkdown.day(source, catalog).entries.stream()
            .filter(e -> e.name.equals("перекус"))
            .findFirst()
            .orElseThrow();
    String result =
        RecipeBlocks.reparent(
            source, RecipeBlocks.piece(entry), group(source, "бутерброд"), null, catalog);
    assertTrue(result.contains("  - перекус 30 ккал"));
    assertFalse(result.contains("- -"));
    survivesFormatting(result, 1, 170);
  }

  @Test
  public void rootInsertionAndCrLfStayCompatible() {
    String source = SOURCE.replace("\n", "\r\n");
    String result =
        RecipeBlocks.reparent(
            source, RecipeBlocks.piece(outside(source)), group(source, "бутерброд"), null, catalog);
    assertTrue(result.contains("бутерброд:\r\n  - сыр 10 г 30 ккал\r\n"));
    assertFalse(result.replace("\r\n", "").contains("\n"));
    var first = group(result, "бутерброд").entries().get(0);
    String promoted = RecipeBlocks.reparent(result, RecipeBlocks.piece(first), null, null, catalog);
    assertTrue(promoted.contains("### Завтрак\r\n- сыр 10 г 30 ккал\r\n"));
    assertEquals(170, VaultMarkdown.day(promoted, catalog).total, 0);
  }
}
