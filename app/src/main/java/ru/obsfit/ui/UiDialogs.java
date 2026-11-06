package ru.obsfit.ui;

import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import ru.obsfit.SportsDiary;
import ru.obsfit.SportsPlan;

/**
 * Presentation and confirmation only. File changes happen in supplied commands after acceptance.
 */
public final class UiDialogs {
  private final Ui ui;

  public UiDialogs(Ui ui) {
    this.ui = ui;
  }

  public void delete(String title, String message, Runnable confirm) {
    new MaterialAlertDialogBuilder(ui.activity)
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton("Отмена", null)
        .setPositiveButton("Удалить", (d, w) -> confirm.run())
        .show();
  }

  public void discard(Runnable leave) {
    new MaterialAlertDialogBuilder(ui.activity)
        .setTitle("Выйти без сохранения?")
        .setNegativeButton("Остаться", null)
        .setPositiveButton("Выйти", (d, w) -> leave.run())
        .show();
  }

  public void menu(String title, String[] options, IntConsumer selected) {
    var dialog =
        new MaterialAlertDialogBuilder(ui.activity).setItems(options, (d, i) -> selected.accept(i));
    if (title != null) dialog.setTitle(title);
    dialog.show();
  }

  public void dates(String[] labels, int current, IntConsumer selected) {
    new MaterialAlertDialogBuilder(ui.activity)
        .setTitle("Ближайшие заметки")
        .setSingleChoiceItems(
            labels,
            current,
            (dialog, index) -> {
              dialog.dismiss();
              selected.accept(index);
            })
        .setNegativeButton("Закрыть", null)
        .show();
  }

  public void workout(
      String title,
      SportsDiary.Exercise exercise,
      SportsDiary.Set initial,
      Consumer<SportsDiary.Set> save) {
    var form = new SportsDiary.Form(initial);
    LinearLayout fields = ui.column();
    fields.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), 0);
    var first =
        ui.field(
            fields,
            exercise.bike()
                ? "Минуты"
                : exercise.paired() ? "Первая рука · повторения" : "Повторения",
            form.first,
            true,
            true);
    ui.watch(first, value -> form.first = value);
    if (exercise.paired() || exercise.bike()) {
      var second =
          ui.field(
              fields,
              exercise.bike() ? "Ккал тренажёра · необязательно" : "Вторая рука · повторения",
              form.second,
              true,
              true);
      ui.watch(second, value -> form.second = value);
    }
    var error = ui.text("", 14, Ui.RED);
    fields.addView(error);
    var dialog =
        new MaterialAlertDialogBuilder(ui.activity)
            .setTitle(title)
            .setView(fields)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        save.accept(form.value(exercise));
                        dialog.dismiss();
                      } catch (IllegalArgumentException failure) {
                        error.setText(failure.getMessage());
                      }
                    }));
    dialog.show();
  }

  public void workoutPlan(SportsDiary.Exercise exercise, String initial, Consumer<String> save) {
    LinearLayout fields = ui.column();
    fields.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), 0);
    var value = ui.field(fields, "Подходы по умолчанию", initial, false, true);
    fields.addView(
        ui.text(
            exercise.bike()
                ? "Например: 20 мин"
                : exercise.paired()
                    ? "Например: 15+15|15+15 · подходы через |, руки через +"
                    : "Например: 35|35 · подходы через |",
            13,
            Ui.MUTED));
    fields.addView(
        ui.text("Сохранится в История спорта.md. Годовые цели останутся прежними.", 13, Ui.MUTED));
    var dialog =
        new MaterialAlertDialogBuilder(ui.activity)
            .setTitle(exercise.name())
            .setView(fields)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        String raw = value.getText().toString();
                        SportsPlan.validate(exercise, raw);
                        save.accept(raw);
                        dialog.dismiss();
                      } catch (IllegalArgumentException failure) {
                        value.setError(failure.getMessage());
                      }
                    }));
    dialog.show();
  }

  public void weight(String initial, Consumer<String> save) {
    LinearLayout fields = ui.column();
    fields.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), 0);
    var value = ui.field(fields, "Вес · кг", initial, true, true);
    var dialog =
        new MaterialAlertDialogBuilder(ui.activity)
            .setTitle("Измеренный вес")
            .setView(fields)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        String raw = value.getText().toString();
                        SportsDiary.positive(raw, true);
                        save.accept(raw);
                        dialog.dismiss();
                      } catch (IllegalArgumentException failure) {
                        value.setError(failure.getMessage());
                      }
                    }));
    dialog.show();
  }

  public void barcode(Consumer<String> selected) {
    LinearLayout fields = ui.column();
    fields.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), 0);
    EditText code = ui.field(fields, "Штрихкод упаковки", "", false, true);
    code.setInputType(InputType.TYPE_CLASS_NUMBER);
    new MaterialAlertDialogBuilder(ui.activity)
        .setTitle("Поиск товара")
        .setView(fields)
        .setNegativeButton("Отмена", null)
        .setPositiveButton("Найти", (d, w) -> selected.accept(code.getText().toString().trim()))
        .show();
  }
}
