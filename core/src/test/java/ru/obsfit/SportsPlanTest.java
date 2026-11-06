package ru.obsfit;

import static org.junit.Assert.*;

import java.io.IOException;
import org.junit.Test;

public class SportsPlanTest {
  private static final String PLAN =
      "# История спорта\nДатаНачала:: 2026-11-01\n"
          + "ПланОтжимания:: 35|25\nПланГантелиБицепс:: 15+14|12+11\n"
          + "ПланГантелиВверх:: 15+15|15+15\nПланПресс:: 25|25\n"
          + "ПланПриседания:: 40|40\nПланВелотренажер:: 20 мин\n"
          + "\n```dataviewjs\nawait dv.view(\"sportHistory\");\n```\nПрочий текст\n";
  private final SportsDiary.Exercise push = SportsDiary.EXERCISES.get(0),
      arms = SportsDiary.EXERCISES.get(1),
      bike = SportsDiary.EXERCISES.get(5);

  @Test
  public void mapsAllKeysIncludingHistoricOverheadName() {
    var plan = new SportsPlan(PLAN);
    assertEquals("35|25", plan.value(push));
    assertEquals("ПланГантелиВверх", SportsPlan.key(SportsDiary.EXERCISES.get(2)));
    assertEquals("15+14|12+11", plan.value(arms));
    assertEquals("20 мин", plan.value(bike));
  }

  @Test
  public void choosesSequentialApproachAndRepeatsLastForExtras() {
    var plan = new SportsPlan(PLAN);
    assertEquals(35, plan.approach(push, 0).first(), 0);
    assertEquals(25, plan.approach(push, 1).first(), 0);
    assertEquals(25, plan.approach(push, 99).first(), 0);
    assertEquals(11, plan.approach(arms, 1).second(), 0);
    assertNull(plan.approach(bike, 0).second());
  }

  @Test
  public void editingOnlyChangesOnePlanPreservingYearAndDataviewBlock() {
    var plan = new SportsPlan(PLAN);
    assertEquals(PLAN.replace("35|25", "40|30|20"), plan.update(push, "40|30|20"));
    assertEquals("15+15|10+10", new SportsPlan(plan.update(arms, "15,15|10,10")).value(arms));
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
    String changed = plan.update(push, "30|20");
    assertEquals("30|20", new SportsPlan(changed).value(push));
    assertFalse(changed.replace("\r\n", "").contains("\n"));
    assertTrue(changed.contains("ПланОтжимания:: 999"));
  }

  @Test
  public void incompleteDuplicateEmptyAndZeroPlansAreExplicitErrors() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SportsPlan(PLAN.replace("ПланОтжимания:: 35|25\n", "")));
    assertThrows(
        IllegalArgumentException.class, () -> new SportsPlan(PLAN + "ПланОтжимания:: 40\n"));
    for (String invalid : new String[] {"", "0|0", "35|", "-1|35", "x", "1.001|35"})
      assertThrows(
          IllegalArgumentException.class, () -> new SportsPlan(PLAN.replace("35|25", invalid)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SportsPlan(PLAN.replace("15+14|12+11", "15+0|15+0")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SportsPlan(PLAN.replace("20 мин", "0 мин 120 ккал")));
  }

  @Test
  public void bikeMinutesRetainPrecisionRatherThanBeingRoundedDuringParsing() {
    var plan = new SportsPlan(PLAN.replace("20 мин", "0.001 мин"));
    assertEquals(.001, plan.approach(bike, 0).first(), 0);
    assertEquals("0.001 мин", plan.value(bike));
  }

  @Test
  public void repositoryWritesSharedFileWithBackupWithoutChangingDailyRecord() throws Exception {
    var storage = new Fixtures.MemoryStorage();
    storage.data.put(SportsPlan.PATH, PLAN);
    var backups = new Fixtures.MemoryBackups();
    var repo = new VaultRepository(storage, backups);
    var initial = repo.loadSportsPlan();
    var saved = repo.saveSportsPlan(initial, push, "40|30");
    assertEquals("40|30", saved.value(push));
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
        VaultRepository.StaleSnapshot.class, () -> repo.saveSportsPlan(initial, push, "40|30"));
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
    assertThrows(IOException.class, () -> repo.saveSportsPlan(original, push, "40|30"));
    assertEquals(PLAN, storage.data.get(SportsPlan.PATH));
    backups.fail = false;
    backups.afterSave = () -> storage.data.put(SportsPlan.PATH, PLAN + "Правка\n");
    assertThrows(
        VaultRepository.StaleSnapshot.class, () -> repo.saveSportsPlan(original, push, "40|30"));
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
    storage.data.put(SportsPlan.PATH, PLAN);
    repo.saveSportsPlan(repo.loadSportsPlan(), push, "35|25");
    assertEquals(0, storage.writes);
    assertTrue(backups.saved.isEmpty());
  }
}
