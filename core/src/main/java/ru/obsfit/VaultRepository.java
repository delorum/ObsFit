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
  private final NoteIndex noteIndex;
  // Accessed only by the single I/O executor. Cache is committed after successful reading/parsing.
  private Snapshot cachedDay;
  private boolean retryRead;
  private Map<String, VaultStorage.Stamp> loadedStamps = Map.of();

  public static List<String> watchedPaths(Note note) {
    return List.of("Еда.md", "Замены.md", note.path);
  }

  private Map<String, VaultStorage.Stamp> stamps(Note note) {
    Map<String, VaultStorage.Stamp> result = new LinkedHashMap<>();
    try {
      for (String path : watchedPaths(note)) {
        var stamp = storage.stamp(path);
        if (stamp == null || !stamp.known()) return Map.of();
        result.put(path, stamp);
      }
    } catch (IOException | UnsupportedOperationException | IllegalArgumentException error) {
      return Map.of();
    }
    return result;
  }

  /** Null means the displayed snapshot remains current. Force bypasses metadata, e.g. on resume. */
  public Snapshot refresh(Note note, Snapshot displayed, boolean force) throws IOException {
    if (!force
        && !retryRead
        && cachedDay != null
        && cachedDay.note.path.equals(note.path)
        && !loadedStamps.isEmpty()
        && loadedStamps.equals(stamps(note))) {
      return cachedDay == displayed ? null : cachedDay;
    }
    return load(note);
  }

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
    noteIndex = new NoteIndex(storage);
  }

  public void validateRoot() throws IOException {
    storage.validateRoot();
    storage.requireDirectory("Дневник/Хроника");
  }

  public List<Note> notes() throws IOException {
    return notes(LocalDate.now(), false);
  }

  public List<Note> notes(LocalDate around, boolean refresh) throws IOException {
    return noteIndex.window(around, refresh);
  }

  public List<Note> moreNotes(LocalDate around) throws IOException {
    return noteIndex.extend(around);
  }

  public Snapshot load(Note note) throws IOException {
    retryRead = true;
    var before = stamps(note);
    String food = storage.read("Еда.md"), replacements = storage.read("Замены.md");
    VaultMarkdown.Catalog catalog = VaultMarkdown.catalog(food);
    Snapshot loaded =
        new Snapshot(
            note, food, replacements, catalog, VaultMarkdown.day(storage.read(note.path), catalog));
    var after = stamps(note);
    loadedStamps = !before.isEmpty() && before.equals(after) ? after : Map.of();
    cachedDay = loaded;
    retryRead = false;
    return loaded;
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
