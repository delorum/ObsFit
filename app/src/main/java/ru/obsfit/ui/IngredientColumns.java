package ru.obsfit.ui;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import java.util.Collection;

/** Column widths are measured once for the entire list, including font scale and actual values. */
final class IngredientColumns {
  private final Ui ui;
  private final int weightWidth, calorieWidth;

  IngredientColumns(Ui ui, Collection<String> weights, Collection<String> calories) {
    this.ui = ui;
    var button = ui.button("", () -> {});
    int measuredWeight = ui.dp(80);
    for (String label : weights)
      measuredWeight =
          Math.max(
              measuredWeight,
              (int)
                  Math.ceil(
                      button.getPaint().measureText(label)
                          + button.getPaddingLeft()
                          + button.getPaddingRight()
                          + ui.dp(8)));
    weightWidth = measuredWeight;
    var text = ui.text("", 13, Ui.MUTED);
    int measuredCalories = ui.dp(64);
    for (String label : calories)
      measuredCalories =
          Math.max(
              measuredCalories, (int) Math.ceil(text.getPaint().measureText(label)) + ui.dp(4));
    calorieWidth = measuredCalories;
  }

  void weight(LinearLayout row, View view) {
    row.addView(view, new LinearLayout.LayoutParams(weightWidth, ui.dp(48)));
  }

  void calories(LinearLayout row, String label) {
    var text = ui.text(label, 13, Ui.MUTED);
    text.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    row.addView(text, new LinearLayout.LayoutParams(calorieWidth, ui.dp(48)));
  }
}
