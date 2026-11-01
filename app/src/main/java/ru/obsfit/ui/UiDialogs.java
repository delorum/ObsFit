package ru.obsfit.ui;

import android.text.InputType;
import android.widget.EditText;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

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

  public void barcode(Consumer<String> selected) {
    EditText code = new EditText(ui.activity);
    code.setInputType(InputType.TYPE_CLASS_NUMBER);
    code.setHint("Штрихкод упаковки");
    new MaterialAlertDialogBuilder(ui.activity)
        .setTitle("Поиск товара")
        .setView(code)
        .setNegativeButton("Отмена", null)
        .setPositiveButton("Найти", (d, w) -> selected.accept(code.getText().toString().trim()))
        .show();
  }
}
