package ru.obsfit;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/**
 * Application persistence service: validation, optimistic concurrency, backups and partial-save
 * errors.
 */
public final class VaultRepository {
  private final VaultStorage storage;
  private final BackupStore backups;

  public static final class Note {
    public final LocalDate date;
    public final String path, name;

    public Note(LocalDate date, String path) {
      this.date = date;
      this.path = path;
      this.name = path.substring(path.lastIndexOf('/') + 1);
    }
  }

  public static final class Snapshot {
    public final Note note;
    public final String foodText, replacementsText;
    public final VaultMarkdown.Catalog catalog;
    public final VaultMarkdown.Day day;

    public Snapshot(
        Note note,
        String food,
        String replacements,
        VaultMarkdown.Catalog catalog,
        VaultMarkdown.Day day) {
      this.note = note;
      foodText = food;
      replacementsText = replacements;
      this.catalog = catalog;
      this.day = day;
    }
  }

  public static final class CatalogSnapshot {
    public final String foodText, replacementsText;
    public final VaultMarkdown.Catalog catalog;

    public CatalogSnapshot(String food, String replacements) {
      foodText = food;
      replacementsText = replacements;
      catalog = VaultMarkdown.catalog(food);
    }
  }

  public static final class StaleSnapshot extends IOException {
    public StaleSnapshot() {
      super(
          "Файл изменился после загрузки, возможно при синхронизации. Обновите данные (↻) и"
              + " повторите действие.");
    }
  }

  public static final class PartialSave extends IOException {
    public PartialSave(IOException cause) {
      super(
          "Еда сохранена в справочник, но дневная запись не добавлена: "
              + cause.getMessage()
              + " Обновите день и выберите её из списка.",
          cause);
    }
  }

  public VaultRepository(VaultStorage storage, BackupStore backups) {
    this.storage = storage;
    this.backups = backups;
  }

  public void validateRoot() throws IOException {
    storage.validateRoot();
    storage.requireDirectory("Дневник/Хроника");
    VaultMarkdown.catalog(storage.read("Еда.md"));
    storage.read("Замены.md");
  }

  public List<Note> notes() throws IOException {
    List<Note> notes = new ArrayList<>();
    for (String path : storage.files("Дневник/Хроника")) {
      LocalDate date = VaultMarkdown.date(path.substring(path.lastIndexOf('/') + 1));
      if (date != null) notes.add(new Note(date, path));
    }
    notes.sort(Comparator.comparing(n -> n.date));
    for (int i = 1; i < notes.size(); i++)
      if (notes.get(i - 1).date.equals(notes.get(i).date))
        throw new IOException(
            "Найдены две ежедневные заметки на "
                + notes.get(i).date
                + ". Устраните дубликат или конфликт Syncthing.");
    return notes;
  }

  public Snapshot load(Note note) throws IOException {
    String food = storage.read("Еда.md"), replacements = storage.read("Замены.md");
    VaultMarkdown.Catalog catalog = VaultMarkdown.catalog(food);
    return new Snapshot(
        note, food, replacements, catalog, VaultMarkdown.day(storage.read(note.path), catalog));
  }

  public CatalogSnapshot loadCatalog() throws IOException {
    return new CatalogSnapshot(storage.read("Еда.md"), storage.read("Замены.md"));
  }

  public synchronized void saveCatalog(CatalogSnapshot original, String updated)
      throws IOException {
    VaultMarkdown.catalog(updated);
    expect("Замены.md", original.replacementsText);
    write("Еда.md", original.foodText, updated, "Еда.md");
  }

  /**
   * SAF cannot transact across two documents. Callers can distinguish a saved catalog from a failed
   * day.
   */
  public synchronized void save(Snapshot original, String noteText, VaultMarkdown.Food newFood)
      throws IOException {
    expect(original.note.path, original.day.source);
    expect("Еда.md", original.foodText);
    expect("Замены.md", original.replacementsText);
    if (newFood != null) {
      write(
          "Еда.md",
          original.foodText,
          VaultMarkdown.addFood(original.catalog, newFood, original.replacementsText),
          "Еда.md");
      try {
        write(original.note.path, original.day.source, noteText, original.note.name);
      } catch (IOException error) {
        throw new PartialSave(error);
      }
    } else write(original.note.path, original.day.source, noteText, original.note.name);
  }

  private void expect(String path, String expected) throws IOException {
    if (!storage.read(path).equals(expected)) throw new StaleSnapshot();
  }

  private void write(String path, String expected, String updated, String name) throws IOException {
    if (expected.equals(updated)) return;
    expect(path, expected);
    String backup = backups.save(name, expected);
    expect(path, expected);
    try {
      storage.write(path, updated);
    } catch (IOException error) {
      throw new IOException(
          "Не удалось завершить запись. Резервная копия: " + backup + ". " + error.getMessage(),
          error);
    }
    if (!storage.read(path).equals(updated))
      throw new IOException(
          "После записи содержимое изменилось. Проверьте синхронизацию; резервная копия: "
              + backup);
  }
}
