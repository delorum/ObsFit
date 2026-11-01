package ru.obsfit;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.*;

/** Only application data and navigation. Never holds Activities, Views or Android Bundles. */
public final class AppState {
  public enum Screen {
    SETTINGS,
    DAY,
    PORTION,
    CATALOG,
    CATALOG_EDITOR
  }

  public enum ScanTarget {
    PORTION,
    CATALOG
  }

  public Screen screen = Screen.SETTINGS;
  public LocalDate selected = LocalDate.now();
  public List<VaultRepository.Note> notes = List.of();
  public VaultRepository.Snapshot day;
  public VaultRepository.CatalogSnapshot catalog;
  public PortionForm portion;
  public CatalogForm catalogForm;
  public PortionForm.Draft returnPortion;
  public final Set<String> expanded = new HashSet<>(VaultMarkdown.SECTIONS);
  public String catalogQuery = "", pendingBarcode, error;
  public ScanTarget scanTarget;
  public int scrollY;
  public boolean busy;

  public record Saved(
      Screen screen,
      String selected,
      PortionForm.Draft portion,
      CatalogForm.Draft catalog,
      PortionForm.Draft returnPortion,
      List<String> expanded,
      String catalogQuery,
      String pendingBarcode,
      ScanTarget scanTarget,
      int scrollY)
      implements Serializable {}

  public Saved save() {
    return new Saved(
        screen,
        selected.toString(),
        portion == null ? null : portion.draft(),
        catalogForm == null ? null : catalogForm.draft(),
        returnPortion,
        List.copyOf(expanded),
        catalogQuery,
        pendingBarcode,
        scanTarget,
        scrollY);
  }
}
