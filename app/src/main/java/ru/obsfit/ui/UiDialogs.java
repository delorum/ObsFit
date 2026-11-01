package ru.obsfit.ui;

import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import ru.obsfit.PortionForm;
import ru.obsfit.VaultMarkdown;

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
        .setTitle("Ежедневные заметки")
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

  public void calories(PortionForm form, Runnable confirm) {
    LinearLayout fields = ui.column();
    fields.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), 0);
    EditText description = ui.field(fields, "Описание (необязательно)", form.name, false, true);
    EditText kcal = ui.field(fields, "Количество, ккал", form.manualKcal, true, true);
    ui.watch(
        description,
        value -> {
          form.name = value;
          form.dirty = true;
        });
    ui.watch(
        kcal,
        value -> {
          form.manualKcal = value;
          form.dirty = true;
        });
    var dialog =
        new MaterialAlertDialogBuilder(ui.activity)
            .setTitle("Калории · " + form.section)
            .setView(fields)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Добавить", null)
            .create();
    dialog.setOnShowListener(
        ignored ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    view -> {
                      description.setError(null);
                      kcal.setError(null);
                      try {
                        VaultMarkdown.number(form.manualKcal);
                      } catch (IllegalArgumentException error) {
                        kcal.setError("Укажите неотрицательное число");
                        kcal.requestFocus();
                        return;
                      }
                      try {
                        form.save();
                      } catch (IllegalArgumentException | IllegalStateException error) {
                        description.setError(error.getMessage());
                        description.requestFocus();
                        return;
                      }
                      dialog.dismiss();
                      confirm.run();
                    }));
    dialog.show();
    if (form.name.isBlank()) kcal.requestFocus();
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
