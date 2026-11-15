package ru.obsfit;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

/** The destination below a recipe is its sibling slot, independent of its visual fold state. */
public class RecipeSiblingDropTest {
  private final VaultMarkdown.Catalog catalog = VaultMarkdown.catalog(Fixtures.FOOD);
  private static final String SOURCE =
      "## Питание\n### Завтрак\n"
          + "Набор:\n  - Блюдо А:\n    - хлеб 40 г 80 ккал\n  - сыр 20 г 60 ккал\n"
          + "  - Блюдо Б:\n    - хлеб 10 г 20 ккал\n  - сыр 10 г 30 ккал\n\n"
          + "### Ужин\nсыр 5 г 15 ккал\n\n### Спорт и диета\nВес:: 101\nСъеденоКалорий:: 205\n";

  private RecipeBlocks.Group group(String source, String name) {
    return VaultMarkdown.day(source, catalog).recipes.stream()
        .filter(g -> g.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private String after(String source, RecipeBlocks.Piece piece, String siblingName) {
    var day = VaultMarkdown.day(source, catalog);
    var sibling = group(source, siblingName);
    var parent = RecipeBlocks.parent(day, sibling.line());
    String changed =
        RecipeBlocks.reparent(source, piece, parent, RecipeBlocks.piece(sibling), catalog);
    var updated = VaultMarkdown.day(changed, catalog);
    assertEquals(day.total, updated.total, 0);
    assertEquals(day.entries.size(), updated.entries.size());
    var before =
        day.entries.stream()
            .map(e -> e.section + ":" + e.name + ":" + e.weight + ":" + e.kcal)
            .sorted()
            .toList();
    var result =
        updated.entries.stream()
            .map(e -> e.section + ":" + e.name + ":" + e.weight + ":" + e.kcal)
            .sorted()
            .toList();
    assertEquals(before, result);
    assertTrue(changed.contains("Вес:: 101"));
    return changed;
  }

  @Test
  public void nestedRecipeDroppedAfterSiblingStaysInsideCommonParent() {
    String changed = after(SOURCE, RecipeBlocks.piece(group(SOURCE, "Блюдо Б")), "Блюдо А");
    var parent = group(changed, "Набор");
    assertEquals(parent.line(), group(changed, "Блюдо А").parentLine());
    assertEquals(parent.line(), group(changed, "Блюдо Б").parentLine());
    assertEquals(
        List.of(40d, 10d, 20d, 10d), parent.entries().stream().map(e -> e.weight).toList());
    assertEquals(1, group(changed, "Блюдо А").entries().size()); // Does not insert into A.
  }

  @Test
  public void ordinaryIngredientDroppedAfterRecipeStaysAtSameNestingLevel() {
    var entry =
        VaultMarkdown.day(SOURCE, catalog).entries.stream()
            .filter(e -> e.raw.equals("  - сыр 10 г 30 ккал"))
            .findFirst()
            .orElseThrow();
    String changed = after(SOURCE, RecipeBlocks.piece(entry), "Блюдо А");
    var parent = group(changed, "Набор");
    assertEquals(
        List.of(40d, 10d, 20d, 10d), parent.entries().stream().map(e -> e.weight).toList());
    var moved = parent.entries().get(1);
    assertEquals("сыр", moved.name);
    assertEquals(
        parent.line(), RecipeBlocks.parent(VaultMarkdown.day(changed, catalog), moved.line).line());
    assertEquals(1, group(changed, "Блюдо А").entries().size());
  }

  @Test
  public void movingEarlierRecipeDownAfterSiblingKeepsItsEntireSubtree() {
    String changed = after(SOURCE, RecipeBlocks.piece(group(SOURCE, "Блюдо А")), "Блюдо Б");
    assertEquals(
        List.of(20d, 10d, 40d, 10d),
        group(changed, "Набор").entries().stream().map(e -> e.weight).toList());
    assertEquals(40, group(changed, "Блюдо А").grams(), 0);
    assertEquals(group(changed, "Набор").line(), group(changed, "Блюдо А").parentLine());
  }

  @Test
  public void nestedSiblingDropWorksThreeLevelsDeep() {
    int start = SOURCE.indexOf("Набор:");
    int end = SOURCE.indexOf("\n\n### Ужин");
    String source =
        SOURCE.substring(0, start)
            + "Внешнее блюдо:\n"
            + SOURCE
                .substring(start, end)
                .lines()
                .map(line -> "  " + line)
                .collect(java.util.stream.Collectors.joining("\n"))
            + SOURCE.substring(end);
    String changed = after(source, RecipeBlocks.piece(group(source, "Блюдо Б")), "Блюдо А");
    assertEquals(group(changed, "Набор").line(), group(changed, "Блюдо Б").parentLine());
    assertEquals(group(changed, "Внешнее блюдо").line(), group(changed, "Набор").parentLine());
    assertEquals(4, group(changed, "Набор").entries().size());
  }

  @Test
  public void topLevelRecipeDroppedAfterAnotherRootRecipeStaysAtMealLevel() {
    String source =
        SOURCE.replace("\n\n### Ужин", "\nОтдельное блюдо:\n  - хлеб 5 г 10 ккал\n\n### Ужин");
    String changed = after(source, RecipeBlocks.piece(group(source, "Набор")), "Отдельное блюдо");
    assertEquals(-1, group(changed, "Набор").parentLine());
    assertEquals(-1, group(changed, "Отдельное блюдо").parentLine());
    assertTrue(group(changed, "Отдельное блюдо").end() <= group(changed, "Набор").line());
    assertEquals(4, group(changed, "Набор").entries().size());
  }
}
