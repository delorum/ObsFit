package ru.obsfit;

import static org.junit.Assert.*;

import org.junit.Test;

public class WeightAnchorTest {
  private static final String SOURCE =
      "## Заметки\nДругой текст\n\n### Спорт и диета\n"
          + "Вес:: 91.5 ^event-synthetic\nОтжимания:: 15|15\nСъеденоКалорий:: 1200\n\n"
          + "## Комментарии\n[[#^event-synthetic|К измерению]]\nТекст комментария\n";

  @Test
  public void displayAndEditInputIgnoreAnchorWhileRawFieldRemainsAvailable() {
    assertEquals("91.5", SportsDiary.weightValue(SOURCE));
    assertEquals("91.5 кг", SportsDiary.summary(SOURCE).weight());
    assertEquals("91.5 ^event-synthetic", SportsDiary.read(SOURCE, "Вес"));
    assertEquals(91.5, SportsDiary.positive(SportsDiary.weightValue(SOURCE), true), 0);
  }

  @Test
  public void changingOnlyNumberPreservesAnchorCommentLinkAndAllOtherBytes() {
    String changed = SportsDiary.weight(SOURCE, "90,75");
    assertEquals(SOURCE.replace("Вес:: 91.5", "Вес:: 90.75"), changed);
    assertEquals("90.75 кг", SportsDiary.summary(changed).weight());
    assertEquals("90.75", SportsDiary.weightValue(changed));
  }

  @Test
  public void clearPreservesAnchorAndReenterUsesTheSameCommentTarget() {
    String cleared = SportsDiary.weight(SOURCE, "");
    assertEquals(SOURCE.replace("Вес:: 91.5 ^", "Вес:: ^"), cleared);
    assertEquals("", SportsDiary.weightValue(cleared));
    assertEquals("—", SportsDiary.summary(cleared).weight());
    assertEquals(SOURCE.replace("91.5", "92"), SportsDiary.weight(cleared, "92"));
  }

  @Test
  public void optionalUnitsCommaAndAnchorOnlyAreDisplayedWithoutMetadata() {
    String source = SOURCE.replace("91.5 ^", "91,25 кг ^");
    assertEquals("91,25", SportsDiary.weightValue(source));
    assertEquals("91.25 кг", SportsDiary.summary(source).weight());
    assertEquals(
        "91.75 ^event-synthetic", SportsDiary.read(SportsDiary.weight(source, "91.75"), "Вес"));
    String anchorOnly = SOURCE.replace("91.5 ^", "^");
    assertEquals("", SportsDiary.weightValue(anchorOnly));
    assertEquals("—", SportsDiary.summary(anchorOnly).weight());
  }

  @Test
  public void tabsCrLfAndTrailingAnchorWhitespaceSurviveNumericEdit() {
    String source =
        SOURCE
            .replace("Вес:: 91.5 ^event-synthetic", "Вес::\t91.5 \t^event-TEST_2  ")
            .replace("\n", "\r\n");
    String changed = SportsDiary.weight(source, "90");
    assertEquals(source.replace("91.5", "90"), changed);
    assertFalse(changed.replace("\r\n", "").contains("\n"));
    assertEquals("90", SportsDiary.weightValue(changed));
  }

  @Test
  public void directWeightWriteAlsoPreservesTheAnchor() {
    assertEquals(SOURCE.replace("91.5", "89.5"), SportsDiary.write(SOURCE, "Вес", "89.5"));
    String tight = SOURCE.replace("Вес:: 91.5", "Вес::91.5");
    assertEquals(tight.replace("91.5", "89"), SportsDiary.weight(tight, "89"));
    assertEquals(
        SOURCE,
        SportsDiary.changeSet(
            SOURCE, SportsDiary.EXERCISES.get(0), 0, new SportsDiary.Set(15, null)));
  }

  @Test
  public void onlyValidTerminalBlockIdsAreHidden() {
    for (String bad :
        new String[] {
          "91.5^event-test", "91.5 ^event-test text", "91.5 ^bad.id", "91.5 [[note]]"
        }) {
      String source = SOURCE.replace("91.5 ^event-synthetic", bad);
      assertEquals(bad, SportsDiary.weightValue(source));
      assertEquals("!", SportsDiary.summary(source).weight());
    }
    assertThrows(IllegalArgumentException.class, () -> SportsDiary.weight(SOURCE, "0"));
    assertThrows(IllegalArgumentException.class, () -> SportsDiary.weight(SOURCE, "92 ^another"));
  }

  @Test
  public void onlySportsSectionWeightIsChanged() {
    String source = "## Заметки\nВес:: 77 ^other\n\n" + SOURCE;
    assertEquals("91.5", SportsDiary.weightValue(source));
    assertEquals(source.replace("Вес:: 91.5", "Вес:: 90"), SportsDiary.weight(source, "90"));
    assertEquals("", SportsDiary.weightValue("## Заметки\nВес:: 77 ^other\n"));
  }

  @Test
  public void repositorySaveKeepsLinkAndStillRejectsConcurrentChanges() throws Exception {
    var storage = new Fixtures.MemoryStorage();
    storage.data.put(Fixtures.PATH, SOURCE);
    var repo = new VaultRepository(storage, new Fixtures.MemoryBackups());
    var snapshot = repo.load(Fixtures.day().note);
    repo.save(snapshot, SportsDiary.weight(snapshot.day.source, "90"), null);
    assertEquals(SOURCE.replace("Вес:: 91.5", "Вес:: 90"), storage.data.get(Fixtures.PATH));
    var refreshed = repo.load(snapshot.note);
    String external = refreshed.day.source + "Внешняя правка\n";
    storage.data.put(Fixtures.PATH, external);
    assertThrows(
        VaultRepository.StaleSnapshot.class,
        () -> repo.save(refreshed, SportsDiary.weight(refreshed.day.source, "89"), null));
    assertEquals(external, storage.data.get(Fixtures.PATH));
  }
}
