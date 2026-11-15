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
  private final ToLongFunction<VaultMarkdown.Food> priority;
  private final CalculatorActions calculator;

  IngredientPicker(
      Ui ui,
      AppState state,
      CatalogForm form,
      Runnable changed,
      ToLongFunction<VaultMarkdown.Food> priority,
      CalculatorActions calculator) {
    this.calculator = calculator;
    this.ui = ui;
    this.state = state;
    this.form = form;
    this.changed = changed;
    this.priority = priority;
  }

  void choose() {
    var available =
        form.original.catalog.foods.stream()
            .filter(
                food ->
                    RecipeIngredients.available(
                        form.original.catalog, food, form.food == null ? null : form.food.name))
            .collect(java.util.stream.Collectors.toList());
    FoodPicker.show(
        ui,
        state,
        available,
        priority,
        food -> {
          if (food.ingredients.isEmpty()) weight(food, -1);
          else {
            form.add(food, food.weight);
            changed.run();
          }
        });
  }

  void weight(VaultMarkdown.Food selected, int index) {
    weight(selected, index, null);
  }

  void weight(VaultMarkdown.Food selected, int index, String initialText) {
    if (state.busy) return;
    if (selected == null && !form.item(index).children().isEmpty()) return;
    if (selected != null && !selected.ingredients.isEmpty()) {
      form.add(selected, selected.weight);
      changed.run();
      return;
    }
    LinearLayout body = ui.column();
    body.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
    String name = selected == null ? form.item(index).name() : selected.name;
    double initial = selected == null ? form.item(index).grams() : selected.weight;
    var dialog =
        new MaterialAlertDialogBuilder(ui.activity)
            .setTitle(name)
            .setView(body)
            .setNegativeButton("Отмена", null)
            .setPositiveButton(selected == null ? "Применить" : "Добавить", null)
            .create();
    EditText weight =
        ui.calculatedField(
            body,
            "Вес, г",
            initialText == null ? Numbers.plain(initial) : initialText,
            true,
            value -> {
              if (state.busy) return;
              dialog.dismiss();
              calculator.ingredientCalculator(
                  state.screen == AppState.Screen.CATALOG_EDITOR,
                  index,
                  selected == null ? null : selected.name,
                  value);
            });
    if (selected != null && !selected.ingredients.isEmpty())
      body.addView(
          ui.text(
              "Составное блюдо сохранится вложенным; веса ингредиентов масштабируются.",
              13,
              MUTED));
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
