package ru.obsfit;

import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Read-only matching history. Each request stops at 20 hits, including midway through a note. */
public final class FoodHistory {
  public static final int PAGE_SIZE = 20;

  private FoodHistory() {}

  public record Hit(
      LocalDate date,
      String path,
      int line,
      String section,
      double grams,
      double calories,
      boolean estimated) {}

  public record Page(List<Hit> hits, boolean done) {}

  public record Draft(String foodName, AppState.Screen source, int sourceScroll)
      implements Serializable {}

  public static final class Form {
    public final VaultMarkdown.Food food;
    public final VaultMarkdown.Catalog catalog;
    public final AppState.Screen source;
    public final int sourceScroll;
    public final List<Hit> hits = new ArrayList<>();
    public boolean loading, done;
    public String error;
    public int scrollY;

    public Form(
        VaultMarkdown.Food food,
        VaultMarkdown.Catalog catalog,
        AppState.Screen source,
        int scroll) {
      this.food = food;
      this.catalog = catalog;
      this.source = source;
      sourceScroll = scroll;
    }

    public Draft draft() {
      return new Draft(food.name, source, sourceScroll);
    }
  }

  public static final class Search {
    private final VaultStorage storage;
    private final NoteIndex index;
    private final VaultMarkdown.Catalog catalog;
    private final VaultMarkdown.Food food;
    private final Deque<VaultRepository.Note> notes = new ArrayDeque<>();
    private final Deque<Hit> matches = new ArrayDeque<>();
    // Kept across a provider failure: retry must neither lose nor duplicate partial-page hits.
    private final List<Hit> buffer = new ArrayList<>();
    private LocalDate through = LocalDate.MAX;
    private boolean namesEnded;
    private volatile boolean cancelled;

    public Search(VaultStorage storage, VaultMarkdown.Catalog catalog, VaultMarkdown.Food food) {
      if (food == null || catalog.get(food.name) != food)
        throw new IllegalArgumentException("Выберите продукт из справочника");
      this.storage = storage;
      this.catalog = catalog;
      this.food = food;
      index = new NoteIndex(storage);
    }

    public void cancel() {
      cancelled = true;
    }

    private void checkCancelled() {
      if (cancelled) throw new CancellationException();
    }

    public Page nextPage() throws IOException {
      while (buffer.size() < PAGE_SIZE) {
        checkCancelled();
        if (!matches.isEmpty()) {
          buffer.add(matches.removeFirst());
          continue;
        }
        if (notes.isEmpty() && !namesEnded) {
          var batch = index.before(through, PAGE_SIZE);
          notes.addAll(batch);
          namesEnded = batch.size() < PAGE_SIZE;
          if (!batch.isEmpty()) through = batch.get(batch.size() - 1).date.minusDays(1);
        }
        if (notes.isEmpty()) break;
        var note = notes.peekFirst();
        checkCancelled();
        String text = storage.read(note.path);
        checkCancelled();
        var found =
            VaultMarkdown.day(text, catalog).entries.stream()
                .filter(entry -> entry.food == food && entry.weight != null)
                .sorted(
                    Comparator.comparingInt((VaultMarkdown.Entry entry) -> entry.line).reversed())
                .map(
                    entry ->
                        new Hit(
                            note.date,
                            note.path,
                            entry.line,
                            entry.section,
                            entry.weight,
                            entry.recordedKcal == null ? entry.kcal : entry.recordedKcal,
                            entry.recordedKcal == null))
                .toList();
        notes.removeFirst();
        matches.addAll(found);
      }
      checkCancelled();
      var result =
          new Page(List.copyOf(buffer), namesEnded && notes.isEmpty() && matches.isEmpty());
      buffer.clear();
      return result;
    }
  }
}
