package ru.obsfit;

import java.time.LocalDate;
import java.util.*;

/** Pure navigation and form transitions. I/O results enter through explicit commands. */
public final class AppController {
  public final AppState state = new AppState();

  public VaultRepository.Note currentNote() {
    return state.notes.stream().filter(n -> n.date.equals(state.selected)).findFirst().orElse(null);
  }

  public static LocalDate nearest(List<VaultRepository.Note> notes, LocalDate date) {
    LocalDate found = notes.get(0).date;
    for (var note : notes) if (!note.date.isAfter(date)) found = note.date;
    return found;
  }

  public boolean canNavigate(int delta) {
    int i = index();
    return i >= 0 && i + delta >= 0 && i + delta < state.notes.size();
  }

  public void navigate(int delta) {
    if (canNavigate(delta)) {
      state.selected = state.notes.get(index() + delta).date;
      state.scrollY = 0;
    }
  }

  private int index() {
    for (int i = 0; i < state.notes.size(); i++)
      if (state.notes.get(i).date.equals(state.selected)) return i;
    return -1;
  }

  public void day(VaultRepository.Snapshot day) {
    state.day = day;
    if (day != null) state.selected = day.note.date;
    state.screen = AppState.Screen.DAY;
    state.portion = null;
    state.catalogForm = null;
    state.returnPortion = null;
    state.pendingBarcode = null;
  }

  public void portion(String section, VaultMarkdown.Entry entry) {
    if (state.day == null) throw new IllegalStateException("Откройте ежедневную заметку");
    state.portion = new PortionForm(state.day, section, entry);
    state.screen = AppState.Screen.PORTION;
    state.pendingBarcode = null;
  }

  public void chooseFood(VaultMarkdown.Food food) {
    state.pendingBarcode = null;
    state.portion.choose(food);
  }

  public void newFood() {
    state.pendingBarcode = null;
    state.portion.startNew();
  }

  public void chooseFromCatalog() {
    state.pendingBarcode = null;
    state.portion.newFood = false;
    state.portion.choosing = true;
  }

  public void catalog(VaultRepository.CatalogSnapshot catalog) {
    state.catalog = catalog;
    state.screen = AppState.Screen.CATALOG;
    state.catalogForm = null;
    state.portion = null;
    state.returnPortion = null;
    state.pendingBarcode = null;
  }

  public void editCatalog(
      VaultRepository.CatalogSnapshot source, VaultMarkdown.Food food, boolean fromPortion) {
    state.returnPortion = fromPortion && state.portion != null ? state.portion.draft() : null;
    state.catalog = source;
    state.catalogForm = new CatalogForm(source, food);
    state.portion = null;
    state.screen = AppState.Screen.CATALOG_EDITOR;
    state.pendingBarcode = null;
  }

  public void returnFromCatalog(VaultRepository.Snapshot refreshed) {
    PortionForm.Draft draft = state.returnPortion;
    state.catalogForm = null;
    state.returnPortion = null;
    state.day = refreshed;
    if (draft == null) {
      state.screen = AppState.Screen.CATALOG;
      return;
    }
    if (refreshed == null) {
      day(null);
      state.error = "Ежедневная заметка больше не найдена.";
      return;
    }
    try {
      state.portion = PortionForm.restore(refreshed, draft);
      state.screen = AppState.Screen.PORTION;
    } catch (IllegalStateException error) {
      day(refreshed);
      state.error = error.getMessage();
    }
  }

  public void queueBarcode(AppState.ScanTarget target, String code) {
    state.pendingBarcode = code;
    state.scanTarget = target;
  }

  public boolean barcodeReady() {
    return state.pendingBarcode != null
        && (state.scanTarget == AppState.ScanTarget.CATALOG
                && state.screen == AppState.Screen.CATALOG_EDITOR
                && state.catalogForm != null
            || state.scanTarget == AppState.ScanTarget.PORTION
                && state.screen == AppState.Screen.PORTION
                && state.portion != null
                && state.portion.newFood);
  }

  public Object barcodeRecipient() {
    return state.scanTarget == AppState.ScanTarget.CATALOG ? state.catalogForm : state.portion;
  }

  public boolean acceptBarcode(Object recipient, String code, String name, Double kcal) {
    if (!barcodeReady()
        || !Objects.equals(code, state.pendingBarcode)
        || recipient != barcodeRecipient()) return false;
    if (state.scanTarget == AppState.ScanTarget.CATALOG) state.catalogForm.barcode(name, kcal);
    else state.portion.barcode(name, kcal);
    state.pendingBarcode = null;
    return true;
  }

  public void restore(
      AppState.Saved saved,
      List<VaultRepository.Note> notes,
      VaultRepository.Snapshot day,
      VaultRepository.CatalogSnapshot catalog) {
    String arrivedBarcode = state.pendingBarcode;
    AppState.ScanTarget arrivedTarget = state.scanTarget;
    state.notes = List.copyOf(notes);
    state.day = day;
    state.catalog = catalog;
    state.portion = null;
    state.catalogForm = null;
    state.returnPortion = null;
    if (saved == null) {
      state.selected = day == null ? LocalDate.now() : day.note.date;
      state.screen = AppState.Screen.DAY;
      return;
    }
    state.selected = LocalDate.parse(saved.selected());
    state.expanded.clear();
    state.expanded.addAll(saved.expanded());
    state.catalogQuery = saved.catalogQuery();
    state.scrollY = saved.scrollY();
    state.pendingBarcode = saved.pendingBarcode();
    state.scanTarget = saved.scanTarget();
    state.returnPortion = saved.returnPortion();
    state.screen = saved.screen();
    try {
      if (saved.screen() == AppState.Screen.CATALOG_EDITOR && saved.catalog() != null)
        state.catalogForm = CatalogForm.restore(catalog, saved.catalog());
      else if (saved.returnPortion() != null && saved.screen() == AppState.Screen.CATALOG_EDITOR)
        returnFromCatalog(day);
      else if (saved.screen() == AppState.Screen.PORTION && saved.portion() != null) {
        if (day == null) throw new IllegalStateException("Ежедневная заметка больше не найдена.");
        state.portion = PortionForm.restore(day, saved.portion());
      } else if (state.screen == AppState.Screen.PORTION
          || state.screen == AppState.Screen.CATALOG_EDITOR) state.screen = AppState.Screen.DAY;
    } catch (IllegalStateException error) {
      state.error = error.getMessage();
      state.screen =
          saved.screen() == AppState.Screen.CATALOG_EDITOR
              ? AppState.Screen.CATALOG
              : AppState.Screen.DAY;
      state.portion = null;
      state.catalogForm = null;
      state.returnPortion = null;
      state.pendingBarcode = null;
    }
    if (day != null) state.selected = day.note.date;
    if (arrivedBarcode != null) {
      queueBarcode(arrivedTarget, arrivedBarcode);
      if (!barcodeReady()) state.pendingBarcode = null;
    }
  }
}
