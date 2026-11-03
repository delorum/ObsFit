package ru.obsfit;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.Locale;

/** Moment ru layout: YYYY/Q/MM-MMMM/gggg-W-неделя (week number is not zero-padded). */
public final class DiaryLayout {
  private static final String[] MONTHS = {
    "январь", "февраль", "март", "апрель", "май", "июнь",
    "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"
  };

  public static String weekFolder(LocalDate date) {
    return String.format(
        Locale.ROOT,
        "%s/%04d/%d/%02d-%s/%04d-%d-неделя",
        NoteIndex.ROOT,
        date.getYear(),
        (date.getMonthValue() - 1) / 3 + 1,
        date.getMonthValue(),
        MONTHS[date.getMonthValue() - 1],
        date.get(IsoFields.WEEK_BASED_YEAR),
        date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
  }

  /** ExternalStorageProvider uses volume:path IDs. Other providers have opaque IDs. */
  public static String externalDocumentId(String authority, String rootId, String relative) {
    if (!"com.android.externalstorage.documents".equals(authority)
        || rootId == null
        || !rootId.contains(":")) return null;
    if (relative.startsWith("/") || relative.contains("//"))
      throw new IllegalArgumentException("Invalid relative path");
    for (String part : relative.split("/"))
      if (part.equals("..") || part.equals("."))
        throw new IllegalArgumentException("Invalid relative path");
    return relative.isEmpty()
        ? rootId
        : rootId + (rootId.endsWith(":") || rootId.endsWith("/") ? "" : "/") + relative;
  }

  private DiaryLayout() {}
}
