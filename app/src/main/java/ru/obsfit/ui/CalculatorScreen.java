package ru.obsfit.ui;

import static ru.obsfit.ui.Ui.*;

import android.content.res.ColorStateList;
import android.widget.*;
import ru.obsfit.*;

/** Keyboard-free arithmetic screen. Expression state and evaluation live in core. */
public final class CalculatorScreen {
  public interface Actions {
    void applyCalculator();
  }

  private final Ui ui;
  private final CalculatorForm form;
  private final Actions actions;

  public CalculatorScreen(Ui ui, CalculatorForm form, Actions actions) {
    this.ui = ui;
    this.form = form;
    this.actions = actions;
  }

  public void render() {
    EditText expression = ui.field(ui.body, "Выражение", form.expression, false, false);
    EditText result = ui.field(ui.body, "Результат", "", false, false);
    expression.setTextColor(INK);
    result.setTextColor(GREEN);
    TextView message = ui.text("", 13, MUTED);
    // Reserve one line even for valid expressions, so the keypad never moves while typing.
    message.setSingleLine(true);
    message.setEllipsize(android.text.TextUtils.TruncateAt.END);
    ui.body.addView(message);
    var apply = ui.button("Использовать результат", actions::applyCalculator);
    apply.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
    apply.setTextColor(BG);
    Runnable update =
        () -> {
          expression.setText(form.expression);
          expression.setSelection(expression.getText().length());
          var preview = form.preview();
          result.setText(preview.value() == null ? "—" : preview.value());
          String error = form.applicationError();
          apply.setEnabled(error == null);
          message.setText(error == null ? "" : error);
          message.setContentDescription(error);
          message.setVisibility(
              error == null ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
        };
    String[][] keys = {
      {"(", ")", "C", "⌫"},
      {"7", "8", "9", "/"},
      {"4", "5", "6", "*"},
      {"1", "2", "3", "-"},
      {"0", ".", "=", "+"}
    };
    for (String[] line : keys) {
      var row = ui.row();
      for (String key : line) {
        String label = key.equals("*") ? "×" : key.equals("/") ? "÷" : key;
        var button =
            ui.button(
                label,
                () -> {
                  form.press(key);
                  update.run();
                });
        button.setTextSize(22);
        button.setMinimumHeight(ui.dp(52));
        button.setContentDescription(
            key.equals("⌫")
                ? "Стереть последний символ"
                : key.equals("C") ? "Очистить выражение" : label);
        ui.weighted(row, button);
      }
      ui.body.addView(row);
    }
    ui.gap(ui.body, 12);
    ui.body.addView(apply, new LinearLayout.LayoutParams(-1, ui.dp(56)));
    update.run();
  }
}
