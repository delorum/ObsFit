package ru.obsfit;

import static org.junit.Assert.*;

import java.io.IOException;
import org.junit.Test;

public class SportsPlanTest {
  private static final String PLAN =
      "# История спорта\n"
          + "ДатаНачала:: 2026-11-01\n"
          + "ГодоваяЦельОтжимания:: 8736\n"
          + "ГодоваяЦельГантелиБицепс:: 3744+3744\n"
          + "ПланОтжимания:: 35|25\n"
          + "ПланГантелиБицепс:: 15+14|12+11\n"
          + "ПланГантелиВверх:: 15+15|15+15\n"
          + "ПланПресс:: 25|25\n"
          + "ПланПриседания:: 40|40\n"
          + "ПланВелотренажер:: 20 мин\n"
          + "\n"
          + "```dataviewjs\n"
          + "await dv.view(\"sportHistory\");\n"
          + "```\n"
          + "Прочий текст\n";
  private final SportsDiary.Exercise push = SportsDiary.EXERCISES.get(0),
      arms = SportsDiary.EXERCISES.get(1),
      bike = SportsDiary.EXERCISES.get(5);

  @Test
  public void mapsAllKeysIncludingHistoricOverheadName() {
    var plan = new SportsPlan(PLAN);
    assertEquals("35", plan.value(push));
    assertEquals("ПланГантелиВверх", SportsPlan.key(SportsDiary.EXERCISES.get(2)));
    assertEquals("15", plan.value(arms));
    assertEquals("20", plan.value(bike));
  }

  @Test
  public void sameSettingAppliesToAllApproachesAndBothArms() {
    var plan = new SportsPlan(PLAN);
    assertEquals(35, plan.approach(push, 0).first(), 0);
    assertEquals(35, plan.approach(push, 1).first(), 0);
    assertEquals(35, plan.approach(push, 99).first(), 0);
    assertEquals(15, plan.approach(arms, 1).first(), 0);
    assertEquals(15, plan.approach(arms, 1).second(), 0);
    assertNull(plan.approach(bike, 0).second());
  }

  @Test
  public void editingOnlyChangesOnePlanPreservingYearAndDataviewBlock() {
    var plan = new SportsPlan(PLAN);
    assertEquals(PLAN.replace("35|25", "40|40"), plan.update(push, "40"));
    assertEquals(PLAN.replace("15+14|12+11", "12+12|12+12"), plan.update(arms, "12"));
  }

  @Test
  public void ignoresProtectedFieldsAndPreservesCrLf() {
    String source =
        "---\n"
            + "ПланОтжимания:: 999\n"
            + "---\n"
            + "> ПланОтжимания:: 999\n"
            + "<!-- ПланОтжимания:: 999 -->\n"
            + "%% ПланОтжимания:: 999 %%\n"
            + "```\n"
            + "ПланОтжимания:: 999\n"
            + "```\n"
            + PLAN;
    var plan = new SportsPlan(source.replace("\n", "\r\n"));
    String changed = plan.update(push, "30");
    assertEquals("30", new SportsPlan(changed).value(push));
    assertFalse(changed.replace("\r\n", "").contains("\n"));
    assertTrue(changed.contains("ПланОтжимания:: 999"));
  }

  @Test
  public void malformedAndMissingValuesUseOriginalFallbackWithoutRewritingSource() {
    for (String invalid : new String[] {"", "0|0", "35|", "-1|35", "x", "1.001|35"}) {
      String source = PLAN.replace("35|25", invalid);
      var plan = new SportsPlan(source);
      assertEquals("35", plan.value(push));
      assertEquals("15", plan.value(arms));
      assertEquals(source, plan.source);
    }
    String missing = PLAN.replace("ПланОтжимания:: 35|25\n", "");
    var plan = new SportsPlan(missing);
    assertEquals("35", plan.value(push));
    assertTrue(plan.update(push, "40").contains("ПланОтжимания:: 40|40"));
    assertTrue(plan.update(push, "40").contains("ГодоваяЦельОтжимания:: 8736"));
    assertThrows(
        IllegalArgumentException.class, () -> new SportsPlan(PLAN + "ПланОтжимания:: 40\n"));
    var empty = new SportsPlan("");
    assertArrayEquals(
        new String[] {"35", "15", "15", "25", "40", "20"},
        SportsDiary.EXERCISES.stream().map(empty::value).toArray(String[]::new));
  }

  @Test
  public void settingsAcceptOnlyOnePositiveNumberAndExpandItToTwoIllustrativeSets() {
    var plan = new SportsPlan(PLAN);
    for (String invalid : new String[] {"", "0", "-1", "35|35", "15+15", "NaN", "1.001"})
      assertThrows(IllegalArgumentException.class, () -> plan.update(push, invalid));
    assertTrue(plan.update(arms, "20").contains("ПланГантелиБицепс:: 20+20|20+20"));
    assertTrue(plan.update(bike, "25,5").contains("ПланВелотренажер:: 25.5 мин"));
    assertFalse(plan.update(bike, "25,5").contains("25.5 мин|"));
    assertNull(
        new SportsPlan(PLAN.replace("20 мин", "20 мин 120 ккал")).approach(bike, 0).second());
  }

  @Test
  public void bikeMinutesRetainPrecisionRatherThanBeingRoundedDuringParsing() {
    var plan = new SportsPlan(PLAN.replace("20 мин", "0.001 мин"));
    assertEquals(.001, plan.approach(bike, 0).first(), 0);
    assertEquals("0.001", plan.value(bike));
  }

  @Test
  public void repositoryWritesSharedFileWithBackupWithoutChangingDailyRecord() throws Exception {
    var storage = new Fixtures.MemoryStorage();
    storage.data.put(SportsPlan.PATH, PLAN);
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(storage, backups);
    var initial = repo.loadSportsPlan();
    var saved = repo.saveSportsPlan(initial, push, "40");
    assertEquals("40", saved.value(push));
    assertEquals(PLAN, backups.saved.get(0));
    assertEquals(Fixtures.DAY, storage.data.get(Fixtures.PATH));
  }

  @Test
  public void syncChangesAreReadFreshAndPreventStaleSettingsOverwrite() throws Exception {
    var storage = new Fixtures.MemoryStorage();
    storage.data.put(SportsPlan.PATH, PLAN);
    var repo = new VaultRepository(storage, new Fixtures.MemoryBackups());
    var initial = repo.loadSportsPlan();
    String remote = PLAN.replace("35|25", "45|30");
    storage.data.put(SportsPlan.PATH, remote);
    assertEquals(45, repo.loadSportsPlan().approach(push, 0).first(), 0);
    assertThrows(
        VaultRepository.StaleSnapshot.class, () -> repo.saveSportsPlan(initial, push, "40"));
    assertEquals(remote, storage.data.get(SportsPlan.PATH));
  }

  @Test
  public void changeDuringBackupAndBackupFailureDoNotOverwriteSharedPlan() throws Exception {
    var storage = new Fixtures.MemoryStorage();
    storage.data.put(SportsPlan.PATH, PLAN);
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(storage, backups);
    var original = repo.loadSportsPlan();
    backups.fail = true;
    assertThrows(IOException.class, () -> repo.saveSportsPlan(original, push, "40"));
    assertEquals(PLAN, storage.data.get(SportsPlan.PATH));
    backups.fail = false;
    backups.afterSave = () -> storage.data.put(SportsPlan.PATH, PLAN + "Правка\n");
    assertThrows(
        VaultRepository.StaleSnapshot.class, () -> repo.saveSportsPlan(original, push, "40"));
    assertEquals(PLAN + "Правка\n", storage.data.get(SportsPlan.PATH));
  }

  @Test
  public void unchangedValueDoesNotCreateBackupAndMissingFileIsNotSilentlyReplaced()
      throws Exception {
    var storage = new Fixtures.MemoryStorage();
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(storage, backups);
    assertThrows(IOException.class, repo::loadSportsPlan);
    assertEquals(0, storage.writes);
    storage.data.put(SportsPlan.PATH, PLAN.replace("35|25", "35|35"));
    repo.saveSportsPlan(repo.loadSportsPlan(), push, "35");
    assertEquals(0, storage.writes);
    assertTrue(backups.saved.isEmpty());
  }
}
