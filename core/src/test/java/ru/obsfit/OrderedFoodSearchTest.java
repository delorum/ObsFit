package ru.obsfit;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class OrderedFoodSearchTest {
  private VaultMarkdown.Food food(String name, String... aliases) {
    return new VaultMarkdown.Food(name, List.of(aliases), "", 100, 100);
  }

  private boolean finds(VaultMarkdown.Food food, String query) {
    return FoodSearch.find(List.of(food), query, f -> 0).contains(food);
  }

  @Test
  public void shortQueriesFindLettersInLongCoffeeName() {
    var coffee = food("растворимый кофе с молоком");
    assertTrue(finds(coffee, "рст"));
    assertTrue(finds(coffee, "ркм"));
  }

  @Test
  public void eachAliasIsSearchedAsAnOrderedSequence() {
    var coffee = food("кофе", "растворимый напиток с молоком");
    assertTrue(finds(coffee, "рст"));
    assertTrue(finds(coffee, "рнм"));
  }

  @Test
  public void lettersCannotBeBorrowedFromDifferentAliases() {
    assertFalse(finds(food("ар", "бс"), "аб"));
  }

  @Test
  public void reversedAndMissingRepeatedLettersDoNotMatch() {
    var coffee = food("растворимый кофе с молоком");
    assertFalse(finds(coffee, "мрст"));
    assertFalse(finds(coffee, "ррр"));
  }

  @Test
  public void twoLettersAndSpacesDoNotRestrictMatchLength() {
    assertTrue(finds(food("чёрный кофе с молоком"), "чм"));
    assertTrue(finds(food("растворимый кофе с молоком"), "р с т к ф м"));
  }

  @Test
  public void normalizationAppliesBeforeOrderedMatching() {
    assertTrue(finds(food("ЧЁРНЫЙ — КОФЕ С МОЛОКОМ"), "ЧЕРКМ"));
  }

  @Test
  public void typoWorkLimitsDoNotExcludeValidLongSubsequences() {
    assertTrue(finds(food("а".repeat(500) + "бв"), "абв"));
    assertTrue(finds(food("а".repeat(500) + "бв"), "а".repeat(150) + "бв"));
  }

  @Test
  public void exactNameOrAliasStillRanksBeforeOrderedAbbreviation() {
    var longName = food("растворимый кофе с молоком");
    var alias = food("другой продукт", "рст");
    var result = FoodSearch.find(List.of(longName, alias), "рст", f -> f == longName ? 999 : 0);
    assertEquals(List.of(alias, longName), result);
  }

  @Test
  public void everyGeneratedOrderedSubsequenceIsIncluded() {
    Random random = new Random(61);
    for (int sample = 0; sample < 500; sample++) {
      StringBuilder name = new StringBuilder(), query = new StringBuilder();
      int length = 20 + random.nextInt(400);
      for (int i = 0; i < length; i++) {
        char letter = (char) ('а' + random.nextInt(6));
        name.append(letter);
        if (i == 0 || random.nextInt(8) == 0) query.append(letter);
      }
      assertTrue(query + " -> " + name, finds(food(name.toString()), query.toString()));
    }
  }
}
