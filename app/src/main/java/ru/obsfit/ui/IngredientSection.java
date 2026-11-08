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
      ToLongFunction<VaultMarkdown.Food> priority,
      boolean allowStructureChanges,
      CalculatorActions calculator) {
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
            priority,
            calculator);
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
    var returned = state.calculator;
    if (returned != null
        && returned.target.ingredient()
        && state.screen == returned.target.source()) {
      state.calculator = null;
      picker.weight(
          returned.ingredientIndex >= 0 ? null : form.original.catalog.get(returned.ingredientName),
          returned.ingredientIndex,
          returned.expression);
    }
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
    java.util.Map<Integer, LinearLayout> parents = new java.util.HashMap<>();
    parents.put(-1, list);
    for (var node : form.nodes()) {
      LinearLayout parent = parents.get(node.parent());
      if (parent == null) continue;
      int index = node.index();
      var item = node.item();
      boolean composite = !item.children().isEmpty();
      String expansion = "ingredient:" + state.screen + ":" + index;
      boolean open = state.expanded.contains(expansion);
      LinearLayout row = ui.row(), labels = ui.column();
      labels.addView(
          ui.text(
              (composite ? (open ? "⌄ " : "› ") : "") + item.name() + (composite ? ":" : ""),
              16,
              INK));
      labels.addView(
          ui.text(
              VaultMarkdown.fmt(
                      RecipeIngredients.portion(
                              form.original.catalog, java.util.List.of(item), item.grams())
                          .calories())
                  + " ккал",
              12,
              MUTED));
      if (composite) {
        labels.setContentDescription(
            item.name() + (open ? ", свернуть" : ", раскрыть ингредиенты"));
        labels.setOnClickListener(
            v -> {
              if (!state.expanded.add(expansion)) state.expanded.remove(expansion);
              refresh();
            });
      }
      ui.weighted(row, labels);
      if (composite) {
        var weight = ui.text(VaultMarkdown.fmt(item.grams()) + " г", 14, MUTED);
        weight.setContentDescription(
            "Вес по ингредиентам: " + item.name() + ", " + VaultMarkdown.fmt(item.grams()) + " г");
        row.addView(weight);
      } else {
        var weightButton =
            ui.button(VaultMarkdown.fmt(item.grams()) + " г", () -> picker.weight(null, index));
        weightButton.setContentDescription("Изменить вес: " + item.name());
        row.addView(weightButton, new LinearLayout.LayoutParams(-2, -2));
      }
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
      parent.addView(row);
      if (composite && open) {
        LinearLayout nested = ui.column();
        nested.setPadding(ui.dp(12), 0, 0, 0);
        parent.addView(nested);
        parents.put(index, nested);
      }
    }
  }
}
