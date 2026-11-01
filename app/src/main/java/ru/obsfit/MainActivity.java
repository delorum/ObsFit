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
  private SharedPreferences prefs;
  private VaultRepository repo;
  private SafVaultStorage storage;
  private FileBackups backups;
  private AppState.Saved restoring;
  private boolean attached, resumed, checkingDay;
  private VaultObserver observer;
  private boolean resumeCheckPending;
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

  private long recent(VaultMarkdown.Food food) {
    return prefs.getLong("recent:" + VaultMarkdown.key(food.name), 0);
  }

  @Override
  public void redraw() {
    new ScreenRenderer(ui, controller, this, this::recent).render();
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
    redraw();
  }

  private void connect(Uri uri, boolean save) {
    AppState.Saved saved = save ? null : restoring;
    task(
        () -> {
          SafVaultStorage candidateStorage = new SafVaultStorage(this, uri);
          VaultRepository candidate = new VaultRepository(candidateStorage, backups);
          candidate.validateRoot();
          var notes = candidate.notes();
          VaultRepository.Snapshot day = null;
          if (!notes.isEmpty()) {
            LocalDate wanted = saved == null ? LocalDate.now() : LocalDate.parse(saved.selected());
            LocalDate selected =
                notes.stream().anyMatch(n -> n.date.equals(wanted))
                    ? wanted
                    : AppController.nearest(notes, wanted);
            VaultRepository.Note note =
                notes.stream().filter(n -> n.date.equals(selected)).findFirst().orElseThrow();
            day = candidate.load(note);
          }
          return new Initial(candidateStorage, candidate, notes, day, candidate.loadCatalog());
        },
        data -> {
          storage = data.storage();
          repo = data.repo();
          if (save) prefs.edit().putString("vault", uri.toString()).apply();
          controller.restore(saved, data.notes(), data.day(), data.catalog());
          restoring = null;
          attached = true;
          redraw();
          consumeBarcode();
        },
        true);
  }

  private record Loaded(List<VaultRepository.Note> notes, VaultRepository.Snapshot day) {}

  private void reload(boolean rescan) {
    if (repo == null || state.busy) return;
    LocalDate selected = state.selected;
    List<VaultRepository.Note> oldNotes = state.notes;
    if (state.screen == AppState.Screen.DAY
        && state.day != null
        && state.selected.equals(state.day.note.date)) state.scrollY = ui.scroll.getScrollY();
    task(
        () -> {
          var notes = rescan ? repo.notes() : oldNotes;
          if (notes.isEmpty()) return new Loaded(notes, null);
          LocalDate date =
              notes.stream().anyMatch(n -> n.date.equals(selected))
                  ? selected
                  : AppController.nearest(notes, selected);
          return new Loaded(
              notes,
              repo.load(notes.stream().filter(n -> n.date.equals(date)).findFirst().orElseThrow()));
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
    state.notes = data.notes();
    controller.day(data.day());
    if (changed) redraw();
    else watchDay();
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
    String[] labels = state.notes.stream().map(n -> n.date.toString()).toArray(String[]::new);
    int selected = 0;
    for (int i = 0; i < state.notes.size(); i++)
      if (state.notes.get(i).date.equals(state.selected)) selected = i;
    dialogs.dates(
        labels,
        selected,
        index -> {
          state.selected = state.notes.get(index).date;
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
    if (state.busy || state.portion == null) return;
    PortionForm form = state.portion;
    try {
      PortionForm.Save save = form.save();
      hideKeyboard();
      task(
          () -> {
            repo.save(form.original, save.noteText(), save.newFood());
            return repo.load(form.original.note);
          },
          day -> {
            if (form.entry == null && !form.manual)
              prefs
                  .edit()
                  .putLong(
                      "recent:" + VaultMarkdown.key(save.recentName()), System.currentTimeMillis())
                  .apply();
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

  private void mutate(VaultRepository.Snapshot original, Work<String> change) {
    state.scrollY = ui.scroll.getScrollY();
    task(
        () -> {
          repo.save(original, change.run(), null);
          return repo.load(original.note);
        },
        day -> {
          controller.day(day);
          redraw();
        });
  }

  @Override
  public void openCatalog() {
    boolean manualRefresh = state.screen == AppState.Screen.CATALOG;
    if (repo == null || state.busy) return;
    task(
        () -> repo.loadCatalog(),
        catalog -> {
          controller.catalog(catalog);
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
      String updated = form.save(), name = form.name;
      hideKeyboard();
      task(
          () -> {
            repo.saveCatalog(form.original, updated);
            return repo.loadCatalog();
          },
          catalog -> {
            if (form.food != null
                && !VaultMarkdown.key(form.food.name).equals(VaultMarkdown.key(name)))
              prefs
                  .edit()
                  .putLong("recent:" + VaultMarkdown.key(name), recent(form.food))
                  .remove("recent:" + VaultMarkdown.key(form.food.name))
                  .apply();
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
      case CATALOG_EDITOR -> {
        if (state.catalogForm != null && state.catalogForm.dirty)
          discard(this::finishCatalogEditor);
        else finishCatalogEditor();
      }
      case PORTION -> {
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
