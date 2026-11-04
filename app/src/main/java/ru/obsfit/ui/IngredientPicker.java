package ru.obsfit.ui;

import static ru.obsfit.ui.Ui.*;

import android.content.DialogInterface;
import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;
import java.util.function.ToLongFunction;
import ru.obsfit.*;

/** Dialog widgets only; adding ingredients and changing weights belong to CatalogForm. */
final class IngredientPicker {
  private final Ui ui;
  private final AppState state;
  private final CatalogForm form;
  private final Runnable changed;
  private final ToLongFunction<VaultMarkdown.Food> recent;

  IngredientPicker(
      Ui ui,
      AppState state,
      CatalogForm form,
      Runnable changed,
      ToLongFunction<VaultMarkdown.Food> recent) {
    this.ui = ui;
    this.state = state;
    this.form = form;
    this.changed = changed;
    this.recent = recent;
  }

  void choose() {
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
    List<VaultMarkdown.Food> available = new ArrayList<>();
    for (var food : form.original.catalog.foods)
      if (RecipeIngredients.available(
          form.original.catalog, food, form.food == null ? null : form.food.name))
        available.add(food);
    Runnable fill =
        () -> {
          list.removeAllViews();
          var matches = FoodSearch.find(available, search.getText().toString(), recent);
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
                  weight(food, -1);
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

  void weight(VaultMarkdown.Food selected, int index) {
    if (state.busy) return;
    LinearLayout body = ui.column();
    body.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
    String name = selected == null ? form.ingredients.get(index).name() : selected.name;
    double initial = selected == null ? form.ingredients.get(index).grams() : selected.weight;
    EditText weight = ui.field(body, "Вес, г", VaultMarkdown.fmt(initial), true, true);
    if (selected != null && !selected.ingredients.isEmpty())
      body.addView(
          ui.text("Блюдо разложится на простые ингредиенты пропорционально весу.", 13, MUTED));
    var dialog =
        new MaterialAlertDialogBuilder(ui.activity)
            .setTitle(name)
            .setView(body)
            .setNegativeButton("Отмена", null)
            .setPositiveButton(selected == null ? "Применить" : "Добавить", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      if (state.busy) return;
                      try {
                        double grams =
                            RecipeIngredients.grams(
                                VaultMarkdown.number(weight.getText().toString()));
                        if (selected == null) form.changeWeight(index, grams);
                        else form.add(selected, grams);
                        changed.run();
                        dialog.dismiss();
                      } catch (IllegalArgumentException error) {
                        weight.setError(error.getMessage());
                      }
                    }));
    dialog.show();
  }
}
