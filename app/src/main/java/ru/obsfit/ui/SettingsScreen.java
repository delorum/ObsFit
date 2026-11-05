package ru.obsfit.ui;

import static ru.obsfit.ui.Ui.*;

import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.LinearLayout;
import ru.obsfit.BuildConfig;
import ru.obsfit.SportsDiary;

public final class SettingsScreen {
  public interface Actions extends HealthSections.Actions {
    String vaultUri();

    boolean connected();

    void chooseVault();

    void exportBackups();

    void openDay();

    void openFoodFacts();
  }

  public static void render(Ui ui, Actions actions) {
    ui.body.addView(ui.bold("Хранилище Obsidian", 23, INK));
    ui.gap(ui.body, 12);
    ui.body.addView(
        ui.text(
            "Выберите корневую папку YaDiskVera. Пока доступ к ней не задан, дневник и справочник"
                + " недоступны.",
            16,
            MUTED));
    ui.gap(ui.body, 16);
    LinearLayout card = ui.column();
    card.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
    card.setBackground(ui.background(CARD, 20));
    card.addView(ui.text("ПАПКА НА ТЕЛЕФОНЕ", 11, ACCENT));
    String path = actions.vaultUri();
    String display = "/Documents/obs/YaDiskVera";
    if (path != null)
      try {
        display =
            "/" + DocumentsContract.getTreeDocumentId(Uri.parse(path)).replace("primary:", "");
      } catch (Exception ignored) {
      }
    card.addView(ui.text(display, 17, INK));
    card.addView(
        ui.text(
            path == null ? "Доступ ещё не предоставлен" : "Папка выбрана через Android",
            12,
            MUTED));
    ui.body.addView(card);
    ui.gap(ui.body, 14);
    ui.body.addView(
        ui.button(
            path == null ? "Выбрать папку хранилища" : "Изменить папку", actions::chooseVault));
    if (actions.connected()) {
      ui.body.addView(ui.button("Открыть дневник", actions::openDay));
      ui.gap(ui.body, 20);
    }
    ui.body.addView(ui.bold("Тренировки · значения подхода", 18, INK));
    ui.body.addView(
        ui.text(
            "Нажмите, чтобы изменить. Применяется к новым подходам; записанные остаются прежними.",
            14,
            MUTED));
    for (var exercise : SportsDiary.EXERCISES) {
      var row = ui.clickRow();
      row.setMinimumHeight(ui.dp(48));
      ui.weighted(row, ui.text(exercise.name(), 16, INK));
      row.addView(ui.text(actions.workoutPreset(exercise), 15, ACCENT));
      row.setOnClickListener(v -> actions.editWorkoutPreset(exercise));
      ui.body.addView(row);
    }
    ui.gap(ui.body, 18);
    ui.body.addView(ui.bold("Дневная норма", 18, INK));
    ui.body.addView(
        ui.text(
            "Из СуточнаяНормаКалорий:: открытого дня, иначе 2150 ккал. Последние 100 ккал до нормы"
                + " — жёлтый, выше нормы — красный.",
            15,
            MUTED));
    ui.gap(ui.body, 18);
    ui.body.addView(ui.bold("Синхронизация и копии", 18, INK));
    ui.body.addView(
        ui.text(
            "Изменения сохраняются прямо в Markdown. Syncthing переносит файлы между устройствами."
                + " Данные перечитываются при открытии приложения, каждые 12 секунд на экране дня и"
                + " по кнопке ↻.",
            15,
            MUTED));
    ui.body.addView(
        ui.text(
            "Перед каждой записью ObsFit сохраняет приватную резервную копию. Хранятся последние"
                + " 100 копий; их можно выгрузить в выбранную папку.",
            15,
            MUTED));
    ui.body.addView(ui.button("Экспортировать резервные копии", actions::exportBackups));
    ui.gap(ui.body, 18);
    ui.body.addView(ui.bold("Штрихкоды", 18, INK));
    ui.body.addView(
        ui.text(
            "Камера распознаёт код на устройстве. Для поиска товара только штрихкод отправляется в"
                + " Open Food Facts. Нужен интернет; база может не содержать товар. Проверяйте"
                + " данные по упаковке. Данные Open Food Facts — ODbL.",
            15,
            MUTED));
    ui.body.addView(ui.button("Open Food Facts ↗", actions::openFoodFacts));
    ui.body.addView(
        ui.text("ObsFit " + BuildConfig.VERSION_NAME + " · Android-прототип", 12, MUTED));
  }
}
