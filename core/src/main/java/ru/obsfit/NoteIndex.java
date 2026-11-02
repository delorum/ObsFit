package ru.obsfit;

import java.io.IOException;
import java.time.*;
import java.time.temporal.*;
import java.util.*;
import java.util.regex.*;

/** Small, lazy windows of note names. Never reads note contents or recursively lists the vault. */
public final class NoteIndex {
  public static final String ROOT = "Дневник/Хроника";
  public static final int NEIGHBORS = 7;
  public static final int PREFETCH_DISTANCE = 2;
  private final VaultStorage storage;
  private final NavigableMap<LocalDate, VaultRepository.Note> loaded = new TreeMap<>();
  private boolean pastEnd, futureEnd;
  private final Map<String, List<VaultStorage.Entry>> directories = new HashMap<>();

  public NoteIndex(VaultStorage storage) {
    this.storage = storage;
  }

  public List<VaultRepository.Note> window(LocalDate around, boolean refresh) throws IOException {
    if (refresh) {
      directories.clear();
      loaded.clear();
      pastEnd = futureEnd = false;
    }
    if (loaded.isEmpty()) {
      var before = seek(around, true, NEIGHBORS + 1);
      var after = seek(around, false, NEIGHBORS);
      merge(before);
      merge(after);
      pastEnd = before.size() < NEIGHBORS + 1;
      futureEnd = after.size() < NEIGHBORS;
    }
    return List.copyOf(loaded.values());
  }

  /** Called in the background after showing a day; adds one page only near a loaded edge. */
  public List<VaultRepository.Note> extend(LocalDate around) throws IOException {
    if (loaded.isEmpty()) return window(around, false);
    if (!pastEnd && loaded.headMap(around, false).size() <= PREFETCH_DISTANCE) {
      var before = seek(loaded.firstKey().minusDays(1), true, NEIGHBORS);
      merge(before);
      pastEnd = before.size() < NEIGHBORS;
    }
    if (!futureEnd && loaded.tailMap(around, false).size() <= PREFETCH_DISTANCE) {
      var after = seek(loaded.lastKey(), false, NEIGHBORS);
      merge(after);
      futureEnd = after.size() < NEIGHBORS;
    }
    return List.copyOf(loaded.values());
  }

  private void merge(List<VaultRepository.Note> notes) throws IOException {
    for (var note : notes) {
      var old = loaded.get(note.date);
      if (old != null && !old.path.equals(note.path))
        throw new IOException("Найдены две ежедневные заметки на " + note.date);
      loaded.put(note.date, note);
    }
  }

  private record Range(LocalDate start, LocalDate end) {
    Range intersect(Range other) {
      LocalDate low = start.isAfter(other.start) ? start : other.start;
      LocalDate high = end.isBefore(other.end) ? end : other.end;
      return low.isAfter(high) ? null : new Range(low, high);
    }
  }

  private record Node(String path, Range range, LocalDate date, int depth) {}

  private LocalDate bound(Node node, LocalDate around, boolean before) {
    return before
        ? (node.range.end.isBefore(around) ? node.range.end : around)
        : (node.range.start.isAfter(around) ? node.range.start : around);
  }

  private List<VaultRepository.Note> seek(LocalDate around, boolean before, int count)
      throws IOException {
    Comparator<LocalDate> order = before ? Comparator.reverseOrder() : Comparator.naturalOrder();
    Comparator<Node> ordering = Comparator.comparing(n -> bound(n, around, before), order);
    PriorityQueue<Node> pending =
        new PriorityQueue<>(
            ordering.thenComparingInt(n -> n.date == null ? 0 : 1).thenComparing(Node::path));
    pending.add(new Node(ROOT, new Range(LocalDate.MIN, LocalDate.MAX), null, 0));
    List<VaultRepository.Note> result = new ArrayList<>();
    while (!pending.isEmpty()) {
      Node node = pending.remove();
      if (before ? node.range.start.isAfter(around) : !node.range.end.isAfter(around)) continue;
      if (result.size() >= count
          && order.compare(bound(node, around, before), result.get(count - 1).date) > 0) break;
      if (node.date != null) {
        for (var previous : result)
          if (previous.date.equals(node.date))
            throw new IOException(
                "Найдены две ежедневные заметки на "
                    + node.date
                    + ". Устраните дубликат или конфликт Syncthing.");
        result.add(new VaultRepository.Note(node.date, node.path));
        continue;
      }
      if (node.depth > 15) continue;
      var entries = directories.get(node.path);
      if (entries == null) {
        entries = List.copyOf(storage.children(node.path));
        directories.put(node.path, entries);
      }
      for (var entry : entries) {
        String name = entry.path().substring(entry.path().lastIndexOf('/') + 1);
        if (entry.directory()) {
          if (name.equals("attachments") || name.equals("assets") || name.startsWith(".")) continue;
          Range range = directoryRange(name, node.range);
          if (range != null) pending.add(new Node(entry.path(), range, null, node.depth + 1));
        } else {
          LocalDate date = VaultMarkdown.date(name);
          if (date != null)
            pending.add(new Node(entry.path(), new Range(date, date), date, node.depth + 1));
        }
      }
    }
    return result;
  }

  private static final Pattern WEEK = Pattern.compile("(\\d{4})-(\\d{2})-неделя");
  private static final Pattern MONTH = Pattern.compile("(0[1-9]|1[0-2])-.+");

  private Range directoryRange(String name, Range parent) {
    try {
      Range own = null;
      Matcher week = WEEK.matcher(name), month = MONTH.matcher(name);
      if (name.matches("\\d{4}")) {
        int year = Integer.parseInt(name);
        own = new Range(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
      } else if (week.matches()) {
        int year = Integer.parseInt(week.group(1)), number = Integer.parseInt(week.group(2));
        if (number >= 1 && number <= 53) {
          LocalDate monday =
              LocalDate.of(year, 1, 4)
                  .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                  .plusWeeks(number - 1);
          own = new Range(monday, monday.plusDays(6));
        }
      } else if (parent.start.getYear() == parent.end.getYear()) {
        int year = parent.start.getYear();
        if (month.matches()) {
          YearMonth ym = YearMonth.of(year, Integer.parseInt(month.group(1)));
          own = new Range(ym.atDay(1), ym.atEndOfMonth());
        } else if (name.matches("[1-4]")
            && parent.start.getDayOfYear() == 1
            && parent.end.getDayOfYear() == parent.end.lengthOfYear()) {
          int first = (Integer.parseInt(name) - 1) * 3 + 1;
          own =
              new Range(LocalDate.of(year, first, 1), YearMonth.of(year, first + 2).atEndOfMonth());
        }
      }
      return own == null ? parent : parent.intersect(own);
    } catch (DateTimeException | NumberFormatException error) {
      return parent;
    }
  }
}
