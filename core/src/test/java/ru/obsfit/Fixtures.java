package ru.obsfit;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

final class Fixtures {
  static final String FOOD =
      """
      # Еда
      Инструкция до таблицы
      <!-- food-table:start -->

      | Название | Ингредиенты | Ккал | Вес |
      | --- | --- | --- | --- |
      | хлеб<br>хл | | 200 | 40 |
      | сыр<br>ср | | 300 | 20 |
      | бутерброд<br>ббр | хл 40 г<br>сыр 20 г | 233.33 | 60 |

      <!-- food-table:end -->
      Инструкция после таблицы
      """;
  static final String REPLACEMENTS = "---\n### другое\nчужой\n";
  static final String PATH = "Дневник/Хроника/2026/2026-11-01-вс.md";
  static final String DAY =
      "## Заметки\n"
          + "Текст дневника\n"
          + "хлеб 40 г 80 ккал\n\n"
          + "## Питание\n"
          + "### Завтрак\n\n"
          + "### Спорт и диета\n"
          + "СъеденоКалорий:: 80\n";

  static VaultRepository.CatalogSnapshot catalog() {
    return new VaultRepository.CatalogSnapshot(FOOD, REPLACEMENTS);
  }

  static VaultRepository.Snapshot day() {
    return day(DAY, FOOD);
  }

  static VaultRepository.Snapshot day(String day, String food) {
    var catalog = VaultMarkdown.catalog(food);
    return new VaultRepository.Snapshot(
        new VaultRepository.Note(LocalDate.of(2026, 11, 1), PATH),
        food,
        REPLACEMENTS,
        catalog,
        VaultMarkdown.day(day, catalog));
  }

  static final class MemoryStorage implements VaultStorage {
    final Map<String, String> data = new LinkedHashMap<>();
    String failWrite, truncateWrite;
    int writes, reads;
    java.util.function.Consumer<String> onRead = path -> {};

    MemoryStorage() {
      data.put("Еда.md", FOOD);
      data.put("Замены.md", REPLACEMENTS);
      data.put(PATH, DAY);
    }

    public void validateRoot() {}

    public void requireDirectory(String path) throws IOException {
      if (data.keySet().stream().noneMatch(p -> p.startsWith(path + "/")))
        throw new IOException("Missing directory");
    }

    public String read(String path) throws IOException {
      reads++;
      onRead.accept(path);
      String text = data.get(path);
      if (text == null) throw new IOException("Missing file: " + path);
      return text;
    }

    public void write(String path, String text) throws IOException {
      writes++;
      if (path.equals(failWrite)) throw new IOException("Simulated provider failure");
      data.put(path, path.equals(truncateWrite) ? text.substring(0, text.length() / 2) : text);
    }

    public List<String> files(String path) {
      return data.keySet().stream().filter(p -> p.startsWith(path + "/")).toList();
    }
  }

  static final class MemoryBackups implements BackupStore {
    final List<String> saved = new ArrayList<>();
    boolean fail;
    Runnable afterSave = () -> {};

    public String save(String name, String content) throws IOException {
      if (fail) throw new IOException("Backup unavailable");
      saved.add(content);
      afterSave.run();
      return "backup-" + saved.size() + "-" + name;
    }
  }
}
