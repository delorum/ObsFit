package ru.obsfit;

import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Read-only matching history. Each request stops at 20 hits, including midway through a note. */
public final class FoodHistory {
  public static final int PAGE_SIZE = 20;
  public static final LocalDate FIRST_DATE = LocalDate.of(2026, 11, 1);

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
    private int pageStart = -1;

    public void beginPage() {
      if (pageStart < 0) pageStart = hits.size();
      loading = true;
      error = null;
    }

    /**
     * Progress is a snapshot of the current page, including an already published prefix on retry.
     */
    public void progress(List<Hit> pageHits) {
      if (pageStart < 0) throw new IllegalStateException("Загрузка страницы не начата");
      int published = hits.size() - pageStart;
      if (pageHits.size() < published) return;
      hits.addAll(pageHits.subList(published, pageHits.size()));
    }

    public void complete(Page page) {
      progress(page.hits());
      done = page.done();
      loading = false;
      pageStart = -1;
    }

    public void failed(String message) {
      loading = false;
      error = message;
    }

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
    private final FoodOccurrences occurrences;
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
      occurrences = new FoodOccurrences(catalog, List.of(food));
      index = new NoteIndex(storage);
    }

    public void cancel() {
      cancelled = true;
    }

    private void checkCancelled() {
      if (cancelled) throw new CancellationException();
    }

    public Page nextPage() throws IOException {
      return nextPage(ignored -> {});
    }

    /**
     * Publish found rows after each note, without waiting for twenty hits or archive exhaustion.
     */
    public Page nextPage(Consumer<List<Hit>> progress) throws IOException {
      checkCancelled();
      if (!buffer.isEmpty()) progress.accept(List.copyOf(buffer));
      while (buffer.size() < PAGE_SIZE) {
        checkCancelled();
        if (!matches.isEmpty()) {
          while (!matches.isEmpty() && buffer.size() < PAGE_SIZE) buffer.add(matches.removeFirst());
          progress.accept(List.copyOf(buffer));
          continue;
        }
        if (notes.isEmpty() && !namesEnded) {
          var batch = index.before(through, PAGE_SIZE, FIRST_DATE);
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
            occurrences.find(VaultMarkdown.day(text, catalog)).stream()
                .filter(entry -> entry.food() == food)
                .sorted(Comparator.comparingInt(FoodOccurrences.Occurrence::line).reversed())
                .map(
                    entry ->
                        new Hit(
                            note.date,
                            note.path,
                            entry.line(),
                            entry.section(),
                            entry.grams(),
                            entry.calories(),
                            entry.estimated()))
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
