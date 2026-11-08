package ru.obsfit;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;
import androidx.appcompat.app.*;
import androidx.core.view.WindowCompat;
import java.io.File;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import ru.obsfit.ui.*;

/**
 * Android lifecycle, permissions and orchestration. Screen rendering and application state are
 * separate.
 */
public final class MainActivity extends AppCompatActivity implements ScreenHost {
  private static final int PICK_VAULT = 101, SCAN = 102, EXPORT = 103, SCAN_CATALOG = 104;
  private final AppController controller = new AppController();
  private final AppState state = controller.state;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private Ui ui;
  private UiDialogs dialogs;
  private ScreenRenderer renderer;
  private FoodHistory.Search historySearch;
  private SharedPreferences prefs;
  private VaultRepository repo;
  private SafVaultStorage storage;
  private FileBackups backups;
  private AppState.Saved restoring;
  private boolean attached, resumed, checkingDay, extendingNotes;
  private VaultObserver observer;
  private boolean resumeCheckPending, refreshNotesPending;
  private final Runnable providerChanged = () -> checkDay(false);

  private void providerChanged() {
    handler.removeCallbacks(providerChanged);
    handler.postDelayed(providerChanged, 600);
  }

  private void watchDay() {
    if (observer == null) return;
    if (resumed && storage != null && state.screen == AppState.Screen.DAY && state.day != null)
      observer.watch(storage.observationUris(state.day.note));
    else observer.close();
  }

  private void checkDay(boolean force) {
    if (!resumed || repo == null || state.screen != AppState.Screen.DAY || state.day == null)
      return;
    resumeCheckPending |= force;
    if (checkingDay || state.busy) return;
    checkingDay = true;
    boolean unconditional = resumeCheckPending;
    resumeCheckPending = false;
    var expected = state.day;
    var source = repo;
    io.execute(
        () -> {
          try {
            var updated = source.refresh(expected.note, expected, unconditional);
            runOnUiThread(
                () -> {
                  checkingDay = false;
                  // A navigation, edit, save or vault switch must win over an older background
                  // result.
                  if (!isDestroyed()
                      && resumed
                      && repo == source
                      && !state.busy
                      && state.screen == AppState.Screen.DAY
                      && state.day == expected
                      && state.selected.equals(expected.note.date)
                      && updated != null) {
                    applyDay(new Loaded(state.notes, updated), false);
                  }
                  if (!isDestroyed() && resumeCheckPending) checkDay(false);
                });
          } catch (Exception error) {
            android.util.Log.d("ObsFitRefresh", "Background refresh failed; will retry", error);
            runOnUiThread(
                () -> {
                  checkingDay = false;
                  // Keep the last successful cache and the current screen. Manual refresh shows
                  // errors.
                  if (!isDestroyed() && resumeCheckPending) checkDay(false);
                });
          }
        });
  }

  private void prefetchNotes() {
    prefetchNotes(false);
  }

  private void prefetchNotes(boolean refresh) {
    refreshNotesPending |= refresh;
    if (isDestroyed()
        || !resumed
        || extendingNotes
        || repo == null
        || state.screen != AppState.Screen.DAY) return;
    extendingNotes = true;
    boolean force = refreshNotesPending;
    refreshNotesPending = false;
    var source = repo;
    var selected = state.selected;
    io.execute(
        () -> {
          try {
            var notes = force ? source.refreshNotes(selected) : source.moreNotes(selected);
            runOnUiThread(
                () -> {
                  extendingNotes = false;
                  if (!isDestroyed() && repo == source && !state.busy) {
                    state.notes = notes;
                    ui.updateNavigation();
                    if (state.screen == AppState.Screen.DAY
                        && notes.stream().noneMatch(note -> note.date.equals(state.selected))) {
                      if (notes.isEmpty()) applyDay(new Loaded(notes, null), false);
                      else reload(false);
                    }
                  }
                  if (!isDestroyed() && refreshNotesPending) prefetchNotes(false);
                });
          } catch (Exception error) {
            android.util.Log.d("ObsFitDays", "Day prefetch failed; retry on navigation", error);
            runOnUiThread(
                () -> {
                  extendingNotes = false;
                  if (!isDestroyed() && refreshNotesPending) prefetchNotes(false);
                });
          }
        });
  }

  private final Runnable mealClock =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed) return;
          ui.updateClock();
          long untilNextMinute = 60_000 - System.currentTimeMillis() % 60_000;
          handler.postDelayed(this, untilNextMinute);
        }
      };

  private final Runnable poll =
      new Runnable() {
        @Override
        public void run() {
          checkDay(false);
          if (resumed) handler.postDelayed(this, 12000);
        }
      };

  @Override
  public void onCreate(Bundle saved) {
    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    super.onCreate(saved);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new androidx.activity.OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                back();
              }
            });
    prefs = getSharedPreferences("obsfit", MODE_PRIVATE);
    ui = new Ui(this);
    observer = new VaultObserver(getContentResolver(), handler, this::providerChanged);
    dialogs = new UiDialogs(ui);
    backups = new FileBackups(new File(getFilesDir(), "backups"));
    if (saved != null) {
      saved.setClassLoader(AppState.Saved.class.getClassLoader());
      restoring = saved.getSerializable("app", AppState.Saved.class);
      String code = saved.getString("arrivedBarcode");
      String target = saved.getString("arrivedTarget");
      if (code != null && target != null)
        controller.queueBarcode(AppState.ScanTarget.valueOf(target), code);
    }
    redraw();
    String root = vaultUri();
    if (root != null) connect(Uri.parse(root), false);
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    if (state.screen == AppState.Screen.DAY && ui.scroll != null)
      state.scrollY = ui.scroll.getScrollY();
    out.putSerializable("app", !attached && restoring != null ? restoring : state.save());
    out.putString("arrivedBarcode", state.pendingBarcode);
    out.putString("arrivedTarget", state.scanTarget == null ? null : state.scanTarget.name());
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    handler.removeCallbacks(mealClock);
    handler.post(mealClock);
    handler.removeCallbacks(poll);
    handler.postDelayed(poll, 12000);
    if (attached && repo != null && state.screen == AppState.Screen.DAY && state.day != null)
      checkDay(true);
    if (attached && repo != null && state.screen == AppState.Screen.SETTINGS) refreshSportsPlan();
    watchDay();
  }

  @Override
  protected void onPause() {
    resumed = false;
    handler.removeCallbacks(poll);
    handler.removeCallbacks(providerChanged);
    handler.removeCallbacks(mealClock);
    if (observer != null) observer.close();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (historySearch != null) historySearch.cancel();
    handler.removeCallbacks(poll);
    handler.removeCallbacks(providerChanged);
    handler.removeCallbacks(mealClock);
    if (observer != null) observer.close();
    io.shutdown();
    super.onDestroy();
  }

  private interface Work<T> {
    T run() throws Exception;
  }

  private <T> void task(Work<T> work, Consumer<T> done) {
    task(work, done, false);
  }

  private <T> void task(Work<T> work, Consumer<T> done, boolean showProgress) {
    if (state.busy || isDestroyed()) return;
    state.refreshing = showProgress;
    state.busy = true;
    state.error = null;
    ui.setBusy(showProgress);
    ui.error(null);
    io.execute(
        () -> {
          try {
            T result = work.run();
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  state.busy = false;
                  state.refreshing = false;
                  ui.setBusy(false);
                  try {
                    done.accept(result);
                    if (resumeCheckPending) checkDay(false);
                  } catch (IllegalArgumentException | IllegalStateException error) {
                    error(error.getMessage());
                  }
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  state.busy = false;
                  state.refreshing = false;
                  ui.setBusy(false);
                  error(error.getMessage() == null ? error.toString() : error.getMessage());
                });
          }
        });
  }

  private void error(String message) {
    state.error = message;
    ui.error(message);
  }

  private void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }

  private long frequency(VaultMarkdown.Food food) {
    String section =
        state.portion != null
            ? state.portion.section
            : state.returnPortion != null ? state.returnPortion.section() : "Заметки";
    return state.mealUsage.frequency(food, section, LocalDate.now());
  }

  private void persistMealUsage() {
    prefs
        .edit()
        .putString("mealUsage:v1", state.mealUsage.encode(LocalDate.now()))
        .putString("mealUsageVault", vaultUri())
        .apply();
  }

  private void updateMealUsage(VaultRepository.Snapshot day) {
    if (day == null) return;
    state.mealUsage.replaceDay(
        day.note.date, new FoodOccurrences(day.catalog).find(day.day), LocalDate.now());
    persistMealUsage();
  }

  private void restoreMealUsage(String vault) {
    state.mealUsage =
        MealUsage.decode(
            vault.equals(prefs.getString("mealUsageVault", null))
                ? prefs.getString("mealUsage:v1", null)
                : null,
            LocalDate.now());
    var editor = prefs.edit();
    for (String key : prefs.getAll().keySet()) if (key.startsWith("recent:")) editor.remove(key);
    editor.apply();
  }

  @Override
  public void redraw() {
    renderer = new ScreenRenderer(ui, controller, this, this::frequency);
    renderer.render();
    watchDay();
  }

  @Override
  public String vaultUri() {
    return prefs.getString("vault", null);
  }

  @Override
  public boolean connected() {
    return repo != null;
  }

  @Override
  public void chooseVault() {
    if (state.busy) return;
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    intent.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    intent.putExtra(
        DocumentsContract.EXTRA_INITIAL_URI,
        DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Documents/obs/YaDiskVera"));
    startActivityForResult(intent, PICK_VAULT);
  }

  @Override
  public void exportBackups() {
    if (state.busy) return;
    if (repo == null) {
      toast("Сначала выберите хранилище");
      return;
    }
    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), EXPORT);
  }

  @Override
  public void openFoodFacts() {
    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://world.openfoodfacts.org")));
  }

  private record Initial(
      SafVaultStorage storage,
      VaultRepository repo,
      List<VaultRepository.Note> notes,
      VaultRepository.Snapshot day,
      VaultRepository.CatalogSnapshot catalog) {}

  @Override
  public void refreshDay() {
    reload(true);
  }

  @Override
  public void openSettings() {
    if (state.busy) return;
    state.screen = AppState.Screen.SETTINGS;
    state.sportsPlan = null;
    redraw();
    refreshSportsPlan();
  }

  private void refreshSportsPlan() {
    if (repo == null || state.busy) return;
    task(
        () -> repo.loadSportsPlan(),
        plan -> {
          state.sportsPlan = plan;
          redraw();
        });
  }

  private void connect(Uri uri, boolean save) {
    AppState.Saved saved = save ? null : restoring;
    task(
        () -> {
          SafVaultStorage candidateStorage = new SafVaultStorage(this, uri);
          VaultRepository candidate = new VaultRepository(candidateStorage, backups);
          candidate.validateRoot();
          LocalDate wanted = saved == null ? LocalDate.now() : LocalDate.parse(saved.selected());
          var notes = candidate.notes(wanted, true);
          VaultRepository.Snapshot day = null;
          if (!notes.isEmpty()) {
            LocalDate selected =
                notes.stream().anyMatch(n -> n.date.equals(wanted))
                    ? wanted
                    : AppController.nearest(notes, wanted);
            VaultRepository.Note note =
                notes.stream().filter(n -> n.date.equals(selected)).findFirst().orElseThrow();
            day = candidate.load(note);
          }
          return new Initial(
              candidateStorage,
              candidate,
              notes,
              day,
              day == null
                  ? candidate.loadCatalog()
                  : new VaultRepository.CatalogSnapshot(day.foodText, day.replacementsText));
        },
        data -> {
          storage = data.storage();
          repo = data.repo();
          state.sportsPlan = null;
          if (save) prefs.edit().putString("vault", uri.toString()).apply();
          restoreMealUsage(uri.toString());
          updateMealUsage(data.day());
          controller.restore(saved, data.notes(), data.day(), data.catalog());
          if (state.screen == AppState.Screen.SETTINGS) refreshSportsPlan();
          restoring = null;
          attached = true;
          redraw();
          if (state.screen == AppState.Screen.FOOD_HISTORY) startFoodHistory();
          prefetchNotes();
          consumeBarcode();
        },
        true);
  }

  private record Loaded(
      List<VaultRepository.Note> notes, VaultRepository.Snapshot day, MealUsage usage) {
    Loaded(List<VaultRepository.Note> notes, VaultRepository.Snapshot day) {
      this(notes, day, null);
    }
  }

  private Loaded loaded(
      List<VaultRepository.Note> notes, VaultRepository.Snapshot day, boolean rebuild)
      throws java.io.IOException {
    return new Loaded(
        notes,
        day,
        rebuild
            ? repo.rebuildMealUsage(
                LocalDate.now(), day == null ? repo.loadCatalog().catalog : day.catalog)
            : null);
  }

  private void reload(boolean rescan) {
    if (repo == null || state.busy) return;
    LocalDate selected = state.selected;
    if (state.screen == AppState.Screen.DAY
        && state.day != null
        && state.selected.equals(state.day.note.date)) state.scrollY = ui.scroll.getScrollY();
    task(
        () -> {
          var notes = repo.notes(selected, false);
          if (notes.isEmpty()) return loaded(notes, null, rescan);
          LocalDate date =
              notes.stream().anyMatch(n -> n.date.equals(selected))
                  ? selected
                  : AppController.nearest(notes, selected);
          var note = notes.stream().filter(n -> n.date.equals(date)).findFirst().orElseThrow();
          VaultRepository.Snapshot day;
          try {
            day = repo.load(note);
          } catch (java.io.FileNotFoundException missing) {
            // Only a deleted selected note requires resolving a replacement before display.
            notes = repo.refreshNotes(selected);
            if (notes.isEmpty()) return loaded(notes, null, rescan);
            LocalDate replacement = AppController.nearest(notes, selected);
            var replacementNote =
                notes.stream().filter(n -> n.date.equals(replacement)).findFirst().orElseThrow();
            day = repo.load(replacementNote);
          }
          return loaded(notes, day, rescan);
        },
        data -> applyDay(data, rescan),
        rescan);
  }

  private void applyDay(Loaded data, boolean forceRedraw) {
    var old = state.day;
    boolean changed =
        forceRedraw
            || state.screen != AppState.Screen.DAY
            || old == null
            || data.day() == null
            || !old.note.path.equals(data.day().note.path)
            || !old.day.source.equals(data.day().day.source)
            || !old.foodText.equals(data.day().foodText);
    if (state.screen == AppState.Screen.DAY
        && ui.scroll != null
        && old != null
        && data.day() != null
        && old.note.path.equals(data.day().note.path)) state.scrollY = ui.scroll.getScrollY();
    if (data.usage() != null) {
      state.mealUsage = data.usage();
      persistMealUsage();
    }
    updateMealUsage(data.day());
    state.notes = data.notes();
    controller.day(data.day());
    if (changed) redraw();
    else watchDay();
    if (forceRedraw) prefetchNotes(true);
    else if (data.day() != null) prefetchNotes();
  }

  @Override
  public void openDay() {
    if (repo == null || state.busy) return;
    reload(false);
  }

  @Override
  public void navigate(int delta) {
    if (state.busy || !controller.canNavigate(delta)) return;
    controller.navigate(delta);
    reload(false);
  }

  @Override
  public void chooseDate() {
    if (state.busy) return;
    var notes = state.notes;
    String[] labels = notes.stream().map(n -> n.date.toString()).toArray(String[]::new);
    int selected = 0;
    for (int i = 0; i < notes.size(); i++)
      if (notes.get(i).date.equals(state.selected)) selected = i;
    dialogs.dates(
        labels,
        selected,
        index -> {
          state.selected = notes.get(index).date;
          state.scrollY = 0;
          reload(false);
        });
  }

  @Override
  public void portion(String section, VaultMarkdown.Entry entry) {
    if (state.busy || state.day == null) return;
    state.scrollY = ui.scroll.getScrollY();
    controller.portion(section, entry);
    redraw();
  }

  @Override
  public void chooseFood(VaultMarkdown.Food food) {
    if (state.busy) return;
    controller.chooseFood(food);
    hideKeyboard();
    redraw();
  }

  @Override
  public void newFood() {
    if (state.busy) return;
    controller.newFood();
    redraw();
  }

  @Override
  public void chooseFromCatalog() {
    if (state.busy) return;
    controller.chooseFromCatalog();
    redraw();
  }

  @Override
  public void manualCalories() {
    if (state.busy || state.portion == null || state.portion.entry != null) return;
    controller.manualCalories();
    hideKeyboard();
    redraw();
  }

  @Override
  public void savePortion() {
    if (state.portion != null) savePortion(() -> state.portion);
  }

  @Override
  public void quickAddFood(VaultMarkdown.Food food) {
    savePortion(() -> controller.quickPortion(food));
  }

  private void savePortion(java.util.function.Supplier<PortionForm> prepare) {
    if (state.busy) return;
    try {
      PortionForm form = prepare.get();
      PortionForm.Save save = form.save();
      hideKeyboard();
      task(
          () -> {
            repo.save(form.original, save.noteText(), save.newFood());
            return repo.load(form.original.note);
          },
          day -> {
            updateMealUsage(day);
            state.expanded.add(form.section);
            controller.day(day);
            redraw();
          });
    } catch (IllegalArgumentException | IllegalStateException error) {
      error(error.getMessage());
      ui.scrollTo(0);
    }
  }

  @Override
  public void entryMenu(VaultMarkdown.Entry entry) {
    if (state.busy) return;
    dialogs.menu(
        null,
        new String[] {"Редактировать", "Переместить", "Удалить"},
        index -> {
          if (index == 0) portion(entry.section, entry);
          else if (index == 1) move(entry);
          else delete(entry);
        });
  }

  @Override
  public void delete(VaultMarkdown.Entry entry) {
    if (state.busy) return;
    var original = state.day;
    dialogs.delete(
        "Удалить запись?",
        entry.name.isBlank() ? entry.canonical() : entry.name,
        () ->
            mutate(
                original,
                () -> VaultMarkdown.edit(original.day.source, entry, null, original.catalog)));
  }

  @Override
  public void move(VaultMarkdown.Entry entry) {
    if (state.busy) return;
    var original = state.day;
    List<String> targets = new ArrayList<>(VaultMarkdown.SECTIONS);
    targets.remove(entry.section);
    dialogs.menu(
        "Переместить в раздел",
        targets.toArray(String[]::new),
        index ->
            mutate(
                original,
                () ->
                    VaultMarkdown.move(
                        original.day.source, entry, targets.get(index), original.catalog)));
  }

  @Override
  public String workoutPreset(SportsDiary.Exercise exercise) {
    return state.sportsPlan == null ? "—" : state.sportsPlan.value(exercise);
  }

  @Override
  public void editWorkoutPreset(SportsDiary.Exercise exercise) {
    if (state.busy || repo == null) return;
    var source = repo;
    task(
        () -> source.loadSportsPlan(),
        plan -> {
          state.sportsPlan = plan;
          dialogs.workoutPlan(
              exercise,
              plan.value(exercise),
              value ->
                  task(
                      () -> source.saveSportsPlan(plan, exercise, value),
                      updated -> {
                        state.sportsPlan = updated;
                        redraw();
                      }));
        });
  }

  @Override
  public void workoutSet(SportsDiary.Exercise exercise, int index, SportsDiary.Set set) {
    if (state.busy || state.day == null) return;
    var original = state.day;
    state.scrollY = ui.scroll.getScrollY();
    try {
      if (index < 0) {
        int next =
            SportsDiary.parse(exercise, SportsDiary.read(original.day.source, exercise.key()))
                .size();
        var source = repo;
        mutate(
            original,
            () -> {
              var plan = source.loadSportsPlan();
              return SportsDiary.changeSet(
                  original.day.source, exercise, next, plan.defaultApproach(exercise));
            });
      } else
        dialogs.workout(
            exercise.name() + " · подход " + (index + 1),
            exercise,
            set,
            value ->
                mutate(
                    original,
                    () -> SportsDiary.changeSet(original.day.source, exercise, index, value)));
    } catch (IllegalArgumentException failure) {
      error(failure.getMessage());
    }
  }

  @Override
  public void removeWorkoutSet(SportsDiary.Exercise exercise, int index) {
    if (state.busy || state.day == null) return;
    var original = state.day;
    dialogs.delete(
        "Удалить подход?",
        exercise.name() + " · " + (index + 1),
        () ->
            mutate(
                original, () -> SportsDiary.changeSet(original.day.source, exercise, index, null)));
  }

  @Override
  public void editWeight() {
    if (state.busy || state.day == null) return;
    var original = state.day;
    try {
      dialogs.weight(
          SportsDiary.read(original.day.source, "Вес"),
          value -> mutate(original, () -> SportsDiary.weight(original.day.source, value)));
    } catch (IllegalArgumentException failure) {
      error(failure.getMessage());
    }
  }

  @Override
  public void removeWeight() {
    if (state.busy || state.day == null) return;
    var original = state.day;
    dialogs.delete(
        "Удалить измеренный вес?",
        state.selected.toString(),
        () -> mutate(original, () -> SportsDiary.weight(original.day.source, "")));
  }

  private void mutate(VaultRepository.Snapshot original, Work<String> change) {
    state.scrollY = ui.scroll.getScrollY();
    task(
        () -> {
          repo.save(original, change.run(), null);
          return repo.load(original.note);
        },
        day -> {
          updateMealUsage(day);
          controller.day(day);
          redraw();
        });
  }

  private record CatalogLoaded(VaultRepository.CatalogSnapshot catalog, MealUsage usage) {}

  @Override
  public void openCatalog() {
    boolean manualRefresh = state.screen == AppState.Screen.CATALOG;
    if (repo == null || state.busy) return;
    task(
        () -> {
          var catalog = repo.loadCatalog();
          return new CatalogLoaded(
              catalog,
              manualRefresh ? repo.rebuildMealUsage(LocalDate.now(), catalog.catalog) : null);
        },
        data -> {
          if (data.usage() != null) {
            state.mealUsage = data.usage();
            persistMealUsage();
          }
          controller.catalog(data.catalog());
          redraw();
        },
        manualRefresh);
  }

  @Override
  public void editCatalog(VaultMarkdown.Food food) {
    if (state.busy) return;
    controller.editCatalog(state.catalog, food, false);
    redraw();
  }

  @Override
  public void catalogMenu(VaultMarkdown.Food food) {
    if (state.busy) return;
    dialogs.menu(
        null,
        new String[] {"Редактировать", "Удалить"},
        index -> {
          if (index == 0) editCatalog(food);
          else deleteCatalog(food);
        });
  }

  @Override
  public void deleteCatalog(VaultMarkdown.Food food) {
    if (state.busy) return;
    var original = state.catalog;
    dialogs.delete(
        "Удалить продукт?",
        food.name,
        () ->
            task(
                () -> {
                  repo.saveCatalog(original, VaultMarkdown.deleteFood(original.catalog, food.name));
                  return repo.loadCatalog();
                },
                catalog -> {
                  controller.catalog(catalog);
                  redraw();
                }));
  }

  @Override
  public void editSelectedFood() {
    if (state.busy || state.portion == null || state.portion.selectedFood == null) return;
    String selected = state.portion.selectedFood.name;
    hideKeyboard();
    task(
        () -> repo.loadCatalog(),
        catalog -> {
          var food = catalog.catalog.get(selected);
          if (food == null) {
            error("Продукт больше не найден в справочнике. Обновите день.");
            return;
          }
          controller.editCatalog(catalog, food, true);
          redraw();
        });
  }

  @Override
  public void saveCatalog() {
    if (state.busy || state.catalogForm == null) return;
    CatalogForm form = state.catalogForm;
    try {
      String updated = form.save();
      hideKeyboard();
      task(
          () -> {
            repo.saveCatalog(form.original, updated);
            return repo.loadCatalog();
          },
          catalog -> {
            state.catalog = catalog;
            finishCatalogEditor();
          });
    } catch (IllegalArgumentException | IllegalStateException error) {
      error(error.getMessage());
    }
  }

  private void finishCatalogEditor() {
    if (state.returnPortion == null) {
      controller.catalog(state.catalog);
      redraw();
      return;
    }
    state.catalogForm = null;
    VaultRepository.Note note = controller.currentNote();
    if (note == null) {
      controller.returnFromCatalog(null);
      redraw();
      return;
    }
    task(
        () -> repo.load(note),
        day -> {
          controller.returnFromCatalog(day);
          redraw();
        });
  }

  @Override
  public void scan(AppState.ScanTarget target) {
    if (state.busy) return;
    state.scanTarget = target;
    startActivityForResult(
        new Intent(this, ScannerActivity.class),
        target == AppState.ScanTarget.CATALOG ? SCAN_CATALOG : SCAN);
  }

  @Override
  public void manualBarcode(AppState.ScanTarget target) {
    if (state.busy) return;
    dialogs.barcode(
        code -> {
          controller.queueBarcode(target, code);
          consumeBarcode();
        });
  }

  private void consumeBarcode() {
    if (state.busy || !controller.barcodeReady()) return;
    String code = state.pendingBarcode;
    Object recipient = controller.barcodeRecipient();
    task(
        () -> ProductLookup.find(code),
        product -> {
          if (!controller.acceptBarcode(recipient, code, product.name(), product.kcal100())) return;
          boolean recipe =
              state.scanTarget == AppState.ScanTarget.CATALOG && state.catalogForm.recipe()
                  || state.scanTarget == AppState.ScanTarget.PORTION && state.portion.recipe();
          redraw();
          toast(
              recipe
                  ? "Название заполнено из Open Food Facts. Калорийность блюда рассчитывается по"
                      + " ингредиентам."
                  : product.kcal100() == null
                      ? "Калорийность не найдена — укажите её по упаковке."
                      : "Данные из Open Food Facts. Проверьте их по упаковке.");
        });
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null) return;
    if (request == PICK_VAULT && data.getData() != null) {
      Uri uri = data.getData();
      try {
        int flags = data.getFlags();
        if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0
            || (flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0)
          throw new SecurityException("Нужен доступ и на чтение, и на запись");
        getContentResolver()
            .takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        restoring = null;
        connect(uri, true);
      } catch (SecurityException error) {
        error("Не удалось сохранить доступ к папке: " + error.getMessage());
      }
    } else if (request == SCAN || request == SCAN_CATALOG) {
      controller.queueBarcode(
          request == SCAN_CATALOG ? AppState.ScanTarget.CATALOG : AppState.ScanTarget.PORTION,
          data.getStringExtra("barcode"));
      consumeBarcode();
    } else if (request == EXPORT && data.getData() != null && storage != null) {
      Uri uri = data.getData();
      task(
          () -> {
            storage.exportBackups(backups.directory(), uri);
            return true;
          },
          done -> toast("Резервные копии экспортированы"));
    }
  }

  @Override
  public void foodHistory() {
    if (state.busy || repo == null) return;
    controller.foodHistory(ui.scroll.getScrollY());
    hideKeyboard();
    redraw();
    startFoodHistory();
  }

  private void startFoodHistory() {
    if (historySearch != null) historySearch.cancel();
    var form = state.foodHistory;
    historySearch = repo.foodHistory(form.catalog, form.food);
    moreFoodHistory();
  }

  @Override
  public void moreFoodHistory() {
    var form = state.foodHistory;
    if (state.screen != AppState.Screen.FOOD_HISTORY
        || form == null
        || form.loading
        || form.done
        || historySearch == null) return;
    var search = historySearch;
    var source = repo;
    form.beginPage();
    renderer.updateFoodHistory();
    io.execute(
        () -> {
          try {
            var page =
                search.nextPage(
                    hits ->
                        runOnUiThread(
                            () -> {
                              if (isDestroyed()
                                  || repo != source
                                  || state.foodHistory != form
                                  || state.screen != AppState.Screen.FOOD_HISTORY
                                  || historySearch != search) return;
                              form.progress(hits);
                              renderer.updateFoodHistory();
                            }));
            runOnUiThread(
                () -> {
                  if (isDestroyed()
                      || repo != source
                      || state.foodHistory != form
                      || state.screen != AppState.Screen.FOOD_HISTORY
                      || historySearch != search) return;
                  form.complete(page);
                  renderer.updateFoodHistory();
                });
          } catch (Exception failure) {
            runOnUiThread(
                () -> {
                  if (isDestroyed()
                      || repo != source
                      || state.foodHistory != form
                      || state.screen != AppState.Screen.FOOD_HISTORY
                      || historySearch != search) return;
                  form.failed(
                      failure.getMessage() == null
                          ? "Не удалось загрузить историю"
                          : failure.getMessage());
                  renderer.updateFoodHistory();
                });
          }
        });
  }

  @Override
  public void calculator(CalculatorForm.Target target) {
    if (state.busy) return;
    controller.calculator(target, -1, null, null, ui.scroll.getScrollY());
    hideKeyboard();
    redraw();
  }

  @Override
  public void ingredientCalculator(boolean catalog, int index, String name, String initial) {
    if (state.busy) return;
    controller.calculator(
        catalog
            ? CalculatorForm.Target.CATALOG_INGREDIENT
            : CalculatorForm.Target.PORTION_INGREDIENT,
        index,
        name,
        initial,
        ui.scroll.getScrollY());
    hideKeyboard();
    redraw();
  }

  @Override
  public void applyCalculator() {
    if (state.busy || state.calculator == null) return;
    int scroll = state.calculator.scrollY;
    try {
      controller.applyCalculator();
      redraw();
      ui.scrollTo(scroll);
    } catch (IllegalArgumentException | IllegalStateException failure) {
      error(failure.getMessage());
    }
  }

  @Override
  public void hideKeyboard() {
    View focus = getCurrentFocus();
    if (focus != null)
      ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(focus.getWindowToken(), 0);
  }

  @Override
  public void back() {
    if (state.busy) {
      toast("Дождитесь завершения операции");
      return;
    }
    switch (state.screen) {
      case FOOD_HISTORY -> {
        int scroll = state.foodHistory.sourceScroll;
        if (historySearch != null) historySearch.cancel();
        historySearch = null;
        controller.closeFoodHistory();
        redraw();
        ui.scrollTo(scroll);
      }
      case CALCULATOR -> {
        int scroll = state.calculator.scrollY;
        controller.cancelCalculator();
        redraw();
        ui.scrollTo(scroll);
      }
      case CATALOG_EDITOR -> {
        if (state.catalogForm != null && state.catalogForm.dirty)
          discard(this::finishCatalogEditor);
        else finishCatalogEditor();
      }
      case PORTION -> {
        if (controller.portionBackToChoices()) {
          hideKeyboard();
          redraw();
          return;
        }
        if (state.portion != null && state.portion.dirty)
          discard(
              () -> {
                controller.day(state.day);
                redraw();
              });
        else {
          controller.day(state.day);
          redraw();
        }
      }
      case CATALOG -> openDay();
      case SETTINGS -> {
        if (repo == null) finish();
        else openDay();
      }
      case DAY -> finish();
    }
  }

  private void discard(Runnable leave) {
    dialogs.discard(leave);
  }
}
