package ru.obsfit.ui;

import static ru.obsfit.ui.Ui.*;

import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;
import java.util.function.ToLongFunction;
import ru.obsfit.*;

/** Shared searchable catalog picker; callers own draft changes. */
final class FoodPicker {
  static void show(
      Ui ui,
      AppState state,
      java.util.List<VaultMarkdown.Food> available,
      ToLongFunction<VaultMarkdown.Food> priority,
      java.util.function.Consumer<VaultMarkdown.Food> selected) {
    if (state.busy) return;
    LinearLayout body = ui.column();
    body.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
    EditText search = ui.field(body, "Найти продукт или блюдо", "", false, true);
    ScrollView scroll = new ScrollView(ui.activity);
    LinearLayout list = ui.column();
    scroll.addView(list);
    body.addView(scroll, new LinearLayout.LayoutParams(-1, ui.dp(300)));
    var dialog =
        new MaterialAlertDialogBuilder(ui.activity)
            .setTitle("Добавить ингредиент")
            .setView(body)
            .setNegativeButton("Отмена", null)
            .create();
    Runnable fill =
        () -> {
          list.removeAllViews();
          var matches = FoodSearch.find(available, search.getText().toString(), priority);
          int shown = 0;
          for (var food : matches) {
            if (shown++ >= 100) break;
            LinearLayout option = ui.column();
            option.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
            option.addView(ui.bold(food.name, 16, INK));
            option.addView(
                ui.text(
                    VaultMarkdown.fmt(food.kcal100)
                        + " ккал/100 г"
                        + (food.ingredients.isEmpty() ? "" : " · составное блюдо"),
                    12,
                    MUTED));
            option.setOnClickListener(
                v -> {
                  dialog.dismiss();
                  selected.accept(food);
                });
            list.addView(option);
          }
          if (matches.isEmpty())
            list.addView(
                ui.text(
                    available.isEmpty()
                        ? "Нет подходящих продуктов. Сначала добавьте их в справочник."
                        : "Ничего не найдено",
                    14,
                    MUTED));
          if (matches.size() > 100)
            list.addView(ui.text("Уточните поиск: показаны первые 100 записей", 12, MUTED));
        };
    ui.watch(search, q -> fill.run());
    fill.run();
    dialog.show();
  }
}
