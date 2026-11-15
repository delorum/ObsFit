package ru.obsfit.ui;

import static ru.obsfit.ui.Ui.*;

import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.function.ToLongFunction;
import ru.obsfit.*;

/** Edits one diary subtree; never writes a catalog recipe. */
public final class DiaryRecipeScreen {
  public interface Actions {
    void saveDiaryRecipe();

    void diaryCalculator(int line, String name, String initial);

    void redraw();
  }

  private final Ui ui;
  private final AppState state;
  private final Actions actions;
  private final ToLongFunction<VaultMarkdown.Food> priority;
  private final DiaryRecipeForm form;

  DiaryRecipeScreen(
      Ui ui, AppState state, Actions actions, ToLongFunction<VaultMarkdown.Food> priority) {
    this.ui = ui;
    this.state = state;
    this.actions = actions;
    this.priority = priority;
    form = state.diaryRecipe;
  }

  void render() {
    var name = ui.field(ui.body, "Название блюда", form.name, false, true);
    ui.watch(
        name,
        value -> {
          form.name = value;
          form.dirty = true;
        });
    ui.body.addView(ui.text("Только эта запись в дневнике", 13, MUTED));
    LinearLayout heading = ui.row();
    ui.weighted(heading, ui.text("Ингредиенты", 18, INK));
    heading.addView(
        ui.icon(
            "＋",
            "Добавить ингредиент",
            () ->
                FoodPicker.show(
                    ui,
                    state,
                    form.original.catalog.foods,
                    priority,
                    food -> {
                      if (!food.ingredients.isEmpty()) {
                        form.add(food, Numbers.plain(food.weight));
                        actions.redraw();
                      } else
                        number(
                            food.name,
                            Numbers.plain(food.weight),
                            -1,
                            value -> form.add(food, value));
                    })),
        ui.actionLayout(48, 48));
    ui.body.addView(heading);
    var day = form.day();
    // Render the recorded tree, including unknown products and calorie-only entries.
    java.util.Map<Integer, Object> items = new java.util.TreeMap<>();
    for (var entry : day.entries) items.put(entry.line, entry);
    for (var group : day.recipes) if (group.line() != 2) items.put(group.line(), group);
    java.util.List<String> weightLabels = new java.util.ArrayList<>(),
        calorieLabels = new java.util.ArrayList<>();
    for (var entry : day.entries) {
      if (entry.weight != null) weightLabels.add(Numbers.plain(entry.weight) + " г");
      calorieLabels.add(VaultMarkdown.fmt(entry.kcal) + " ккал");
    }
    for (var group : day.recipes) {
      weightLabels.add(VaultMarkdown.fmt(group.grams()) + " г");
      calorieLabels.add(VaultMarkdown.fmt(group.calories()) + " ккал");
    }
    var columns = new IngredientColumns(ui, weightLabels, calorieLabels);
    for (var item : items.entrySet()) {
      int line = item.getKey();
      var row = ui.row();
      int depth = 0;
      for (var group : day.recipes)
        if (group.line() != 2 && line > group.line() && line < group.end()) depth++;
      row.setPadding(ui.dp(depth * 12), 0, 0, 0);
      if (item.getValue() instanceof RecipeBlocks.Group group) {
        ui.weighted(row, ui.text(group.name() + ":", 16, INK));
        var weightLabel = ui.text(VaultMarkdown.fmt(group.grams()) + " г", 14, MUTED);
        weightLabel.setGravity(android.view.Gravity.CENTER);
        columns.weight(row, weightLabel);
        columns.calories(row, VaultMarkdown.fmt(group.calories()) + " ккал");
      } else {
        var entry = (VaultMarkdown.Entry) item.getValue();
        ui.weighted(row, ui.text(entry.name.isBlank() ? "Калории" : entry.name, 16, INK));
        if (entry.weight != null)
          columns.weight(
              row,
              ui.button(
                  Numbers.plain(entry.weight) + " г",
                  () ->
                      number(
                          entry.name,
                          Numbers.plain(entry.weight),
                          line,
                          value -> form.weight(line, value))));
        else columns.weight(row, new android.widget.Space(ui.activity));
        columns.calories(row, VaultMarkdown.fmt(entry.kcal) + " ккал");
      }
      row.addView(
          ui.icon(
              "×",
              "Убрать ингредиент",
              () -> {
                if (state.busy) return;
                form.remove(line);
                actions.redraw();
              }),
          ui.actionLayout(44, 48));
      ui.body.addView(row);
    }
    if (day.entries.isEmpty()) ui.body.addView(ui.text("Добавьте ингредиенты", 14, MUTED));
    ui.body.addView(ui.text(VaultMarkdown.fmt(day.total) + " ккал", 18, ACCENT));
    ui.body.addView(ui.button("Сохранить", actions::saveDiaryRecipe));
  }

  private void number(
      String title, String initial, int line, java.util.function.Consumer<String> save) {
    if (state.busy) return;
    var body = ui.column();
    body.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
    var dialog =
        new MaterialAlertDialogBuilder(ui.activity)
            .setTitle(title)
            .setView(body)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Применить", null)
            .create();
    var input =
        ui.calculatedField(
            body,
            "Вес, г",
            initial,
            true,
            value -> {
              dialog.dismiss();
              actions.diaryCalculator(line, title, value);
            });
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      if (state.busy) return;
                      try {
                        save.accept(input.getText().toString());
                        dialog.dismiss();
                        actions.redraw();
                      } catch (IllegalArgumentException error) {
                        input.setError(error.getMessage());
                      }
                    }));
    dialog.show();
  }
}
