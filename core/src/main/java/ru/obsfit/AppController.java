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
    state.calculator = null;
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

  /** Prepares a default portion without changing the visible form or its recoverable draft. */
  public PortionForm quickPortion(VaultMarkdown.Food food) {
    if (state.screen != AppState.Screen.PORTION
        || state.portion == null
        || !state.portion.choosing
        || state.portion.entry != null)
      throw new IllegalStateException("Быстрое добавление доступно в списке выбора новой порции");
    var source = state.portion;
    if (food == null || source.original.catalog.get(food.name) != food)
      throw new IllegalStateException("Список еды изменился. Выберите продукт заново.");
    var quick = new PortionForm(source.original, source.section, null);
    quick.choose(food);
    return quick;
  }

  public void newFood() {
    state.pendingBarcode = null;
    state.portion.startNew();
  }

  public void manualCalories() {
    state.pendingBarcode = null;
    state.portion.startManual();
  }

  public void chooseFromCatalog() {
    state.pendingBarcode = null;
    state.portion.manual = false;
    state.portion.newFood = false;
    state.portion.choosing = true;
  }

  public boolean portionBackToChoices() {
    var form = state.portion;
    if (state.screen != AppState.Screen.PORTION
        || form == null
        || form.entry != null
        || form.choosing
        || form.newFood
        || form.manual
        || form.selectedFood == null) return false;
    chooseFromCatalog();
    form.dirty = false;
    return true;
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

  public void calculator(
      CalculatorForm.Target target, int index, String name, String initial, int scrollY) {
    if (state.screen != target.source()) throw new IllegalStateException("Откройте исходную форму");
    String value = calculatorValue(target, index, name);
    state.calculator =
        new CalculatorForm(target, index, name, target.ingredient() ? initial : value, scrollY);
    state.screen = AppState.Screen.CALCULATOR;
    state.error = null;
  }

  private CatalogForm ingredientForm(CalculatorForm.Target target) {
    if (target == CalculatorForm.Target.CATALOG_INGREDIENT) {
      if (state.catalogForm == null) throw new IllegalStateException("Форма справочника закрыта");
      return state.catalogForm;
    }
    if (state.portion == null
        || state.portion.choosing
        || state.portion.manual
        || !(state.portion.newFood || state.portion.selectedRecipe()))
      throw new IllegalStateException("В этой порции нет редактируемого состава");
    return state.portion.composition;
  }

  private String calculatorValue(CalculatorForm.Target target, int index, String name) {
    if (target.ingredient()) {
      var form = ingredientForm(target);
      if (index >= 0) {
        if (index >= form.ingredients.size())
          throw new IllegalStateException("Ингредиент больше не найден");
        return Numbers.plain(form.ingredients.get(index).grams());
      }
      if (name == null
          || form.original.catalog.get(name) == null
          || target == CalculatorForm.Target.PORTION_INGREDIENT && !state.portion.newFood)
        throw new IllegalStateException("Выберите ингредиент");
      if (!RecipeIngredients.available(
          form.original.catalog,
          form.original.catalog.get(name),
          form.food == null ? null : form.food.name))
        throw new IllegalStateException("Продукт не может входить в собственный состав");
      return Numbers.plain(form.original.catalog.get(name).weight);
    }
    if (target.source() == AppState.Screen.PORTION) {
      var form = state.portion;
      if (form == null || form.choosing) throw new IllegalStateException("Форма порции закрыта");
      return switch (target) {
        case PORTION_WEIGHT -> {
          if (form.manual) throw new IllegalStateException("У ручных калорий нет веса");
          yield form.weight;
        }
        case PORTION_DEFAULT_WEIGHT -> {
          if (!form.newFood || form.recipe())
            throw new IllegalStateException("Вес рассчитывается автоматически");
          yield form.defaultWeight;
        }
        case PORTION_KCAL100 -> {
          if (form.manual || form.recipe() || form.selectedFood != null)
            throw new IllegalStateException("Калорийность доступна только в редакторе справочника");
          yield form.kcal100;
        }
        case PORTION_CALORIES -> {
          if (!form.manual) throw new IllegalStateException("Откройте режим ручных калорий");
          yield form.manualKcal;
        }
        default -> throw new IllegalStateException("Неизвестное поле порции");
      };
    }
    var form = state.catalogForm;
    if (form == null || form.recipe())
      throw new IllegalStateException("Поле справочника рассчитывается автоматически");
    return target == CalculatorForm.Target.CATALOG_WEIGHT ? form.weight : form.kcal100;
  }

  public void applyCalculator() {
    if (state.screen != AppState.Screen.CALCULATOR || state.calculator == null)
      throw new IllegalStateException("Калькулятор закрыт");
    var calc = state.calculator;
    String result = calc.result();
    calculatorValue(calc.target, calc.ingredientIndex, calc.ingredientName);
    if (calc.target.ingredient()) {
      // Return to the ingredient's numeric input; its normal Apply button commits the draft.
      calc.expression = result;
      state.screen = calc.target.source();
      return;
    } else
      switch (calc.target) {
        case PORTION_WEIGHT -> state.portion.changePortionWeight(result);
        case PORTION_DEFAULT_WEIGHT -> {
          state.portion.defaultWeight = result;
          state.portion.dirty = true;
        }
        case PORTION_KCAL100 -> {
          state.portion.kcal100 = result;
          state.portion.dirty = true;
        }
        case PORTION_CALORIES -> {
          state.portion.manualKcal = result;
          state.portion.dirty = true;
        }
        case CATALOG_WEIGHT -> {
          state.catalogForm.weight = result;
          state.catalogForm.dirty = true;
        }
        case CATALOG_KCAL100 -> {
          state.catalogForm.kcal100 = result;
          state.catalogForm.dirty = true;
        }
        default -> throw new IllegalStateException("Неизвестное поле");
      }
    cancelCalculator();
  }

  public void cancelCalculator() {
    if (state.calculator == null) throw new IllegalStateException("Калькулятор закрыт");
    state.screen = state.calculator.target.source();
    state.calculator = null;
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
    state.calculator = null;
    if (saved == null) {
      state.selected = day == null ? LocalDate.now() : day.note.date;
      state.screen = AppState.Screen.DAY;
      return;
    }
    state.selected = LocalDate.parse(saved.selected());
    state.expanded.clear();
    for (String section : saved.expanded()) state.expanded.add(VaultMarkdown.sectionName(section));
    state.catalogQuery = saved.catalogQuery();
    state.scrollY = saved.scrollY();
    state.pendingBarcode = saved.pendingBarcode();
    state.scanTarget = saved.scanTarget();
    state.returnPortion = saved.returnPortion();
    var sourceScreen =
        saved.screen() == AppState.Screen.CALCULATOR && saved.calculator() != null
            ? saved.calculator().target().source()
            : saved.screen();
    state.screen = sourceScreen;
    try {
      if (sourceScreen == AppState.Screen.CATALOG_EDITOR && saved.catalog() != null)
        state.catalogForm = CatalogForm.restore(catalog, saved.catalog());
      else if (saved.returnPortion() != null && sourceScreen == AppState.Screen.CATALOG_EDITOR)
        returnFromCatalog(day);
      else if (sourceScreen == AppState.Screen.PORTION && saved.portion() != null) {
        if (day == null) throw new IllegalStateException("Ежедневная заметка больше не найдена.");
        state.portion = PortionForm.restore(day, saved.portion());
      } else if (state.screen == AppState.Screen.PORTION
          || state.screen == AppState.Screen.CATALOG_EDITOR) state.screen = AppState.Screen.DAY;
      if (saved.screen() == AppState.Screen.CALCULATOR) {
        if (saved.calculator() == null)
          throw new IllegalStateException("Не найден черновик калькулятора");
        var draft = saved.calculator();
        calculatorValue(draft.target(), draft.ingredientIndex(), draft.ingredientName());
        state.calculator = CalculatorForm.restore(draft);
        state.screen = AppState.Screen.CALCULATOR;
      }
    } catch (IllegalStateException error) {
      state.error = error.getMessage();
      state.screen =
          sourceScreen == AppState.Screen.CATALOG_EDITOR
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
