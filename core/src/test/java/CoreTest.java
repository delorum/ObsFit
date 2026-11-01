import java.nio.file.*;
import java.util.*;
import ru.obsfit.VaultMarkdown;

public final class CoreTest {
  static int checks = 0;

  static void equal(Object expected, Object actual) {
    checks++;
    if (!Objects.equals(expected, actual))
      throw new AssertionError("Expected " + expected + ", got " + actual);
  }

  static void near(double expected, double actual) {
    checks++;
    if (Math.abs(expected - actual) > 0.001)
      throw new AssertionError("Expected " + expected + ", got " + actual);
  }

  static void rejects(Runnable code) {
    checks++;
    try {
      code.run();
    } catch (IllegalArgumentException | IllegalStateException ok) {
      return;
    }
    throw new AssertionError("Expected rejection");
  }

  static String table(String rows) {
    return "Предисловие\n"
        + "<!-- food-table:start -->\n\n"
        + "| Название | Ингредиенты | Ккал | Вес |\n"
        + "| --- | --- | --- | --- |\n"
        + rows
        + "\n\n<!-- food-table:end -->\nИнструкция после таблицы\n";
  }

  static final String FOOD =
      table(
          "| белый хлеб<br>бт | | 200 | 40 |\n"
              + "| колбаса<br>клб | | 300 | 20 |\n"
              + "| бутерброд<br>ббр | бт 40 г<br>клб 20 г | | |");

  public static void main(String[] args) throws Exception {
    var catalog = VaultMarkdown.catalog(FOOD);
    near(60, catalog.get("ББР").weight);
    near(233.33333, catalog.get("ббр").kcal100);
    String source =
        "[[Предыдущий день]]\n\n"
            + "## Заметки\n"
            + "2026-11-01 10:47:02\n"
            + "Обычный текст\n"
            + "бт\n"
            + "бт 60\n"
            + "150\n"
            + "150 ккал\n\n"
            + "## Питание\n"
            + "### Завтрак\n"
            + "ббр\n\n"
            + "### Обед\n"
            + "Не еда\n\n"
            + "## Здоровье\n"
            + "### Спорт и диета\n"
            + "СъеденоКалорий:: 999\n\n"
            + "## Конец\n"
            + "Неприкосновенный текст\n";
    var day = VaultMarkdown.day(source, catalog);
    near(490, day.total);
    equal(4, day.entries.size());
    equal(3, day.section("Заметки").size());
    equal(1, day.section("Завтрак").size());
    String recalculated = VaultMarkdown.recalculate(source, catalog);
    equal(true, recalculated.contains("СъеденоКалорий:: 490"));
    equal(true, recalculated.contains("\n150\n"));
    equal(true, recalculated.contains("белый хлеб 60 г 120 ккал"));
    equal(recalculated, VaultMarkdown.recalculate(recalculated, catalog));
    var first = day.entries.get(0);
    String moved = VaultMarkdown.move(source, first, "Обед", catalog);
    var movedDay = VaultMarkdown.day(moved, catalog);
    near(490, movedDay.total);
    equal(1, movedDay.section("Обед").size());
    equal(2, movedDay.section("Заметки").size());
    equal(true, moved.contains("Неприкосновенный текст\n"));
    equal(true, moved.contains("2026-11-01 10:47:02\nОбычный текст\n"));
    String deleted = VaultMarkdown.edit(source, first, null, catalog);
    near(410, VaultMarkdown.day(deleted, catalog).total);
    String edited =
        VaultMarkdown.edit(source, first, VaultMarkdown.portion("белый хлеб", 80, 200), catalog);
    near(570, VaultMarkdown.day(edited, catalog).total);
    rejects(
        () ->
            VaultMarkdown.edit(
                source.replace("\nбт\n", "\nдругая строка\n"), first, null, catalog));
    String added = VaultMarkdown.add(source, "Ужин", "колбаса 30 г 90 ккал", catalog);
    equal(true, added.contains("### Ужин"));
    near(580, VaultMarkdown.day(added, catalog).total);
    equal(1, VaultMarkdown.day(added, catalog).section("Ужин").size());
    String minimal = VaultMarkdown.add("## Прочее\nТекст\n", "Завтрак", "бт", catalog);
    equal(true, minimal.contains("## Питание"));
    equal(true, minimal.contains("СъеденоКалорий:: 80"));
    near(80, VaultMarkdown.day(minimal, catalog).total);
    String protectedText =
        "---\n"
            + "value: 100 ккал\n"
            + "---\n"
            + "```js\n"
            + "бт\n"
            + "<!-- незакрытый комментарий внутри кода\n"
            + "100 ккал\n"
            + "```\n"
            + "> бт\n"
            + "> 200 ккал\n"
            + "# 300 ккал\n"
            + "| хлеб | 100 ккал |\n"
            + "ShortDescr:: 100 ккал\n"
            + "`бт`\n"
            + "~~бт~~\n"
            + "<!--\n"
            + "бт\n"
            + "-->\n"
            + "%%\n"
            + "бт\n"
            + "%%\n"
            + "~~~\n"
            + "бт\n"
            + "~~~\n"
            + "бт\n";
    near(80, VaultMarkdown.day(protectedText, catalog).total);
    equal(0, VaultMarkdown.day("150\n150,5\n- 300\n 150 ", catalog).entries.size());
    near(123.5, VaultMarkdown.day("другая еда 123,5 ккал", catalog).total);
    near(99, VaultMarkdown.day("неизвестная еда 50 г 99 ккал", catalog).total);
    near(80, VaultMarkdown.day("белый хлеб 40 г 999 ккал", catalog).total);
    equal(
        true,
        VaultMarkdown.recalculate("## Заметки\r\nбт\r\nСъеденоКалорий::\r\n", catalog)
            .contains("СъеденоКалорий:: 80\r\n"));
    rejects(() -> VaultMarkdown.catalog(table("| хлеб<br>ХЛЕБ | | 100 | 40 |")));
    rejects(() -> VaultMarkdown.catalog(table("| хлеб | | -1 | 40 |")));
    rejects(() -> VaultMarkdown.catalog(table("| хлеб | | 100 | 0 |")));
    rejects(() -> VaultMarkdown.catalog(table("| бутер | бутер 40 г | | |")));
    rejects(() -> VaultMarkdown.catalog(table("| бутер | неизвестно 40 г | | |")));
    near(0, VaultMarkdown.catalog(table("| вода | | 0 | 100 |")).get("вода").kcal100);
    var fresh = new VaultMarkdown.Food("каша", List.of("кш"), "", 150, 200);
    String updated = VaultMarkdown.addFood(catalog, fresh, "---\n### иное\nалиас\n");
    equal(4, VaultMarkdown.catalog(updated).foods.size());
    equal(true, updated.endsWith("Инструкция после таблицы\n"));
    near(150, VaultMarkdown.catalog(updated).get("кш").kcal100);
    for (String duplicate : List.of("каша", "КШ", "КАША"))
      rejects(() -> VaultMarkdown.addFood(catalog, fresh, "---\n### " + duplicate + "\n"));
    rejects(() -> VaultMarkdown.addFood(catalog, fresh, "---\n### другое\nКШ\n"));
    rejects(
        () ->
            VaultMarkdown.addFood(
                catalog, new VaultMarkdown.Food("бт", List.of(), "", 100, 40), "---\n"));
    rejects(
        () ->
            VaultMarkdown.addFood(
                catalog,
                new VaultMarkdown.Food("текст\n\nинъекция", List.of(), "", 100, 40),
                "---\n"));
    equal(java.time.LocalDate.of(2026, 11, 1), VaultMarkdown.date("2026-11-01-вс.md"));
    equal(null, VaultMarkdown.date("2026-11-01-вс.sync-conflict.md"));
    // Optional read-only compatibility check against a real vault. No content is printed or
    // written.
    if (args.length > 0) {
      Path vault = Path.of(args[0]);
      var actual = VaultMarkdown.catalog(Files.readString(vault.resolve("Еда.md")));
      equal(true, actual.foods.size() > 0);
      try (var files = Files.walk(vault.resolve("Дневник/Хроника"))) {
        Path note =
            files
                .filter(p -> p.getFileName().toString().equals("2026-11-01-вс.md"))
                .findFirst()
                .orElseThrow();
        String text = Files.readString(note);
        String normalized = VaultMarkdown.recalculate(text, actual);
        equal(normalized, VaultMarkdown.recalculate(normalized, actual));
      }
    }
    System.out.println("ObsFit Markdown: " + checks + " checks passed");
  }
}
