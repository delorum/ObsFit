package ru.obsfit;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class SportsDiaryTest {
  private final SportsDiary.Exercise push = SportsDiary.EXERCISES.get(0),
      arms = SportsDiary.EXERCISES.get(1),
      bike = SportsDiary.EXERCISES.get(5);

  @Test
  public void newAppStartsCollapsed() {
    assertTrue(new AppController().state.expanded.isEmpty());
  }

  @Test
  public void singleApproachParsingDoesNotInventBikeCalories() {
    assertNull(SportsDiary.preset(bike, "20 мин").second());
    assertEquals("15+15", SportsDiary.preset(arms, "15+15").format(arms));
  }

  @Test
  public void oldArmSyntaxAndAsymmetricSetsRoundTrip() {
    String source = Fixtures.DAY + "ГантелиБицепс:: 15,14|13+12\n";
    var sets = SportsDiary.parse(arms, SportsDiary.read(source, arms.key()));
    assertEquals(14, sets.get(0).second(), 0);
    source = SportsDiary.changeSet(source, arms, 1, new SportsDiary.Set(11, 10d));
    assertEquals("15+14|11+10", SportsDiary.read(source, arms.key()));
    source = SportsDiary.changeSet(source, arms, 0, null);
    assertEquals("11+10", SportsDiary.read(source, arms.key()));
    source = SportsDiary.changeSet(source, arms, 0, null);
    assertEquals("", SportsDiary.read(source, arms.key()));
  }

  @Test
  public void addsAndEditsSetsWithoutTouchingFoodOrOtherExercises() {
    String source = Fixtures.DAY + "Отжимания:: 10|12\nПресс:: 20|20\nВес:: 103,4\n";
    String changed = SportsDiary.changeSet(source, push, 2, SportsDiary.preset(push, "35"));
    assertEquals(source.replace("10|12", "10|12|35"), changed);
    changed = SportsDiary.changeSet(changed, push, 1, SportsDiary.preset(push, "25"));
    assertEquals("10|25|35", SportsDiary.read(changed, push.key()));
    assertEquals("20|20", SportsDiary.read(changed, "Пресс"));
    assertEquals(80, VaultMarkdown.day(changed, VaultMarkdown.catalog(Fixtures.FOOD)).total, 0);
  }

  @Test
  public void bikePreservesExplicitEnergyAndMissingEnergy() {
    var sets = SportsDiary.parse(bike, "10 мин 52 ккал|12,5 мин|20 мин 0 ккал");
    assertEquals(52, sets.get(0).second(), 0);
    assertNull(sets.get(1).second());
    assertEquals("12.5 мин", sets.get(1).format(bike));
    assertEquals(0, sets.get(2).second(), 0);
  }

  @Test
  public void weightCreateEditClearAndCommaDecimal() {
    String source = SportsDiary.weight(Fixtures.DAY, "102,35");
    assertEquals("102.35", SportsDiary.read(source, "Вес"));
    assertEquals("101.2", SportsDiary.read(SportsDiary.weight(source, "101.2"), "Вес"));
    assertEquals("", SportsDiary.read(SportsDiary.weight(source, ""), "Вес"));
    assertTrue(source.contains("СъеденоКалорий:: 80"));
    for (String invalid : List.of("0", "-1", "NaN", "Infinity", "103 кг", "1\n2"))
      assertThrows(IllegalArgumentException.class, () -> SportsDiary.weight(source, invalid));
  }

  @Test
  public void protectedBlocksAndOtherSectionsAreNeverWritten() {
    String source =
        "---\n"
            + "Вес:: 999\n"
            + "---\n"
            + "## Заметки\n"
            + "Вес:: 998\n"
            + "### Спорт и диета\n"
            + "```\n"
            + "Вес:: 997\n"
            + "```\n"
            + "> Вес:: 996\n"
            + "<!-- Вес:: 995 -->\n"
            + "%% Вес:: 994 %%\n"
            + "Вес:: 103\n"
            + "## Дальше\n"
            + "Вес:: 993\n";
    assertEquals("103", SportsDiary.read(source, "Вес"));
    assertEquals(source.replace("Вес:: 103", "Вес:: 102"), SportsDiary.weight(source, "102"));
  }

  @Test
  public void missingFieldStaysInSportsSectionAndCrLfIsPreserved() {
    String source = "## Спорт и диета\r\nПресс:: 20\r\n## Далее\r\nТекст\r\n";
    String changed = SportsDiary.weight(source, "100");
    assertTrue(changed.contains("## Спорт и диета\r\nВес:: 100\r\nПресс:: 20"));
    assertFalse(changed.replace("\r\n", "").contains("\n"));
    assertEquals("", SportsDiary.read("## Заметки\nВес:: 99\n", "Вес"));
    assertEquals("99", SportsDiary.read(SportsDiary.weight("## Заметки\nТекст\n", "99"), "Вес"));
  }

  @Test
  public void insertsMissingFieldBeforeProtectedTail() {
    String source = "## Спорт и диета\n```\nВес:: 999\n";
    String changed = SportsDiary.weight(source, "100");
    assertEquals("100", SportsDiary.read(changed, "Вес"));
    assertTrue(changed.endsWith("```\nВес:: 999\n"));
  }

  @Test
  public void ambiguityAndInvalidSetsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SportsDiary.weight("## Спорт и диета\nВес:: 1\nВес:: 2\n", "99"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SportsDiary.weight("## Спорт и диета\n## Спорт и диета\n", "99"));
    for (String raw : List.of("15", "15+15|", "-1+2", "1.5+2", "2+3+4"))
      assertThrows(IllegalArgumentException.class, () -> SportsDiary.parse(arms, raw));
    assertThrows(IllegalArgumentException.class, () -> SportsDiary.preset(push, "35|35"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SportsDiary.changeSet(Fixtures.DAY, push, 5, new SportsDiary.Set(1, null)));
  }

  @Test
  public void repositoryStillRejectsConcurrentSyncthingChangesAndCreatesBackup() throws Exception {
    var storage = new Fixtures.MemoryStorage();
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(storage, backups);
    var original = repo.load(Fixtures.day().note);
    String changed = SportsDiary.weight(original.day.source, "102");
    storage.data.put(Fixtures.PATH, original.day.source + "Другой текст\n");
    assertThrows(VaultRepository.StaleSnapshot.class, () -> repo.save(original, changed, null));
    assertTrue(storage.data.get(Fixtures.PATH).endsWith("Другой текст\n"));
    var refreshed = repo.load(Fixtures.day().note);
    repo.save(refreshed, SportsDiary.weight(refreshed.day.source, "102"), null);
    assertFalse(backups.saved.isEmpty());
    assertEquals("102", SportsDiary.read(storage.data.get(Fixtures.PATH), "Вес"));
  }
}
