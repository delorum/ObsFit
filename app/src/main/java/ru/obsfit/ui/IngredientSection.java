package ru.obsfit.ui;

import static ru.obsfit.ui.Ui.*;

import android.view.View;
import android.widget.*;
import java.util.function.ToLongFunction;
import ru.obsfit.*;

/** Shared composition editor; existing diary recipes expose only ingredient weight changes. */
final class IngredientSection {
  private final Ui ui;
  private final AppState state;
  private final CatalogForm form;
  private final Runnable changed;
  private final IngredientPicker picker;
  private final boolean allowStructureChanges;
  private LinearLayout ingredients, list;
  private com.google.android.material.button.MaterialButton toggle;

  IngredientSection(
      Ui ui,
      AppState state,
      CatalogForm form,
      Runnable changed,
      ToLongFunction<VaultMarkdown.Food> recent) {
    this(ui, state, form, changed, recent, true);
  }

  IngredientSection(
      Ui ui,
      AppState state,
      CatalogForm form,
      Runnable changed,
      ToLongFunction<VaultMarkdown.Food> recent,
      boolean allowStructureChanges) {
    this.allowStructureChanges = allowStructureChanges;
    this.ui = ui;
    this.state = state;
    this.form = form;
    this.changed = changed;
    picker =
        new IngredientPicker(
            ui,
            state,
            form,
            () -> {
              refresh();
              changed.run();
            },
            recent);
  }

  void render(LinearLayout parent) {
    toggle =
        ui.button(
            "›  Ингредиенты",
            () -> {
              form.ingredientsExpanded = !form.ingredientsExpanded;
              refresh();
            });
    parent.addView(toggle, new LinearLayout.LayoutParams(-1, ui.dp(52)));
    ingredients = ui.column();
    ingredients.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(12));
    ingredients.setBackground(ui.background(CARD, 18));
    list = ui.column();
    ingredients.addView(list);
    if (allowStructureChanges) {
      ingredients.addView(ui.button("＋ Добавить из справочника", picker::choose));
      ingredients.addView(
          ui.text(
              "Выберите продукты и их вес. Вес блюда и калорийность рассчитаются автоматически.",
              13,
              MUTED));
    } else {
      ingredients.addView(ui.text("Вес ингредиентов — только для этой порции.", 13, MUTED));
    }
    parent.addView(ingredients);
    ui.gap(parent, 12);
    refresh();
  }

  void refresh() {
    String arrow = form.ingredientsExpanded ? "⌄" : "›";
    toggle.setText(
        form.ingredients.isEmpty()
            ? ui.activity.getString(ru.obsfit.R.string.ingredients_heading, arrow)
            : ui.activity.getString(
                ru.obsfit.R.string.ingredients_count_heading, arrow, form.ingredients.size()));
    ingredients.setVisibility(form.ingredientsExpanded ? View.VISIBLE : View.GONE);
    list.removeAllViews();
    if (form.ingredients.isEmpty())
      list.addView(ui.text("Добавьте продукты, чтобы собрать составное блюдо.", 14, MUTED));
    for (int i = 0; i < form.ingredients.size(); i++) {
      int index = i;
      var item = form.ingredients.get(i);
      LinearLayout row = ui.row(), labels = ui.column();
      labels.addView(ui.text(item.name(), 16, INK));
      VaultMarkdown.Food food = form.original.catalog.get(item.name());
      if (food != null)
        labels.addView(ui.text(Math.round(food.kcal100 * item.grams() / 100) + " ккал", 12, MUTED));
      ui.weighted(row, labels);
      var weightButton =
          ui.button(VaultMarkdown.fmt(item.grams()) + " г", () -> picker.weight(null, index));
      weightButton.setContentDescription("Изменить вес: " + item.name());
      var weightParams = new LinearLayout.LayoutParams(-2, -2);
      row.addView(weightButton, weightParams);
      if (allowStructureChanges)
        row.addView(
            ui.icon(
                "×",
                "Убрать ингредиент " + item.name(),
                () -> {
                  if (state.busy) return;
                  form.remove(index);
                  refresh();
                  changed.run();
                }),
            ui.actionLayout(44, 48));
      list.addView(row);
    }
  }
}
