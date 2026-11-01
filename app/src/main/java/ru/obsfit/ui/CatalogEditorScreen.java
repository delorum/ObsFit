package ru.obsfit.ui;

import static ru.obsfit.ui.Ui.*;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.*;
import java.util.function.ToLongFunction;
import ru.obsfit.*;

public final class CatalogEditorScreen {
  public interface Actions {
    void saveCatalog();
  }

  private final Ui ui;
  private final AppState state;
  private final CatalogForm form;
  private final Actions actions;
  private final IngredientPicker picker;
  private EditText weight, kcal;
  private LinearLayout ingredients, list;
  private com.google.android.material.button.MaterialButton toggle;
  private TextView calculation;
  private boolean refreshing;

  public CatalogEditorScreen(
      Ui ui, AppState state, Actions actions, ToLongFunction<VaultMarkdown.Food> recent) {
    this.ui = ui;
    this.state = state;
    form = state.catalogForm;
    this.actions = actions;
    picker =
        new IngredientPicker(
            ui,
            state,
            form,
            () -> {
              renderIngredients();
              preview();
            },
            recent);
  }

  public void render() {
    EditText name = ui.field(ui.body, "Название", form.name, false, true);
    EditText aliases = ui.field(ui.body, "Алиасы: по одному на строке", form.aliases, false, true);
    ui.multiline(aliases, 2);
    weight = ui.field(ui.body, "Вес по умолчанию, г", form.weight, true, true);
    kcal = ui.field(ui.body, "Калорийность на 100 г", form.kcal100, true, true);
    toggle =
        ui.button(
            "›  Ингредиенты",
            () -> {
              form.ingredientsExpanded = !form.ingredientsExpanded;
              renderIngredients();
            });
    ui.body.addView(toggle, new LinearLayout.LayoutParams(-1, ui.dp(52)));
    ingredients = ui.column();
    ingredients.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(12));
    ingredients.setBackground(ui.background(CARD, 18));
    list = ui.column();
    ingredients.addView(list);
    ingredients.addView(ui.button("＋ Добавить из справочника", picker::choose));
    ingredients.addView(
        ui.text(
            "Выберите продукты и их вес. Вес блюда и калорийность рассчитаются автоматически.",
            13,
            MUTED));
    ui.body.addView(ingredients);
    ui.gap(ui.body, 12);
    calculation = ui.text("", 16, ACCENT);
    ui.body.addView(calculation);
    ui.gap(ui.body, 12);
    var save = ui.button("Сохранить", actions::saveCatalog);
    save.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
    save.setTextColor(BG);
    ui.body.addView(save, new LinearLayout.LayoutParams(-1, ui.dp(56)));
    if (form.food != null)
      ui.body.addView(
          ui.text(
              "При переименовании прежнее название сохраняется как алиас для записей дневника."
                  + " Изменение справочника не переписывает прошлые заметки.",
              13,
              MUTED));
    ui.watch(
        name,
        value -> {
          if (refreshing) return;
          form.name = value;
          form.dirty = true;
          preview();
        });
    ui.watch(
        aliases,
        value -> {
          if (refreshing) return;
          form.aliases = value;
          form.dirty = true;
          preview();
        });
    ui.watch(
        weight,
        value -> {
          if (refreshing) return;
          form.weight = value;
          form.dirty = true;
          preview();
        });
    ui.watch(
        kcal,
        value -> {
          if (refreshing) return;
          form.kcal100 = value;
          form.dirty = true;
          preview();
        });
    renderIngredients();
    preview();
  }

  private void preview() {
    CatalogForm.Preview result = form.preview();
    refreshing = true;
    weight.setEnabled(!form.recipe());
    kcal.setEnabled(!form.recipe());
    if (!weight.getText().toString().equals(form.weight)) weight.setText(form.weight);
    if (!kcal.getText().toString().equals(form.kcal100)) kcal.setText(form.kcal100);
    refreshing = false;
    calculation.setText(
        result.error() == null
            ? "Порция по умолчанию: " + VaultMarkdown.fmt(result.portionCalories()) + " ккал"
            : result.error());
    calculation.setTextColor(result.error() == null ? ACCENT : MUTED);
  }

  private void renderIngredients() {
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
      row.addView(
          ui.icon(
              "×",
              "Убрать ингредиент " + item.name(),
              () -> {
                if (state.busy) return;
                form.remove(index);
                renderIngredients();
                preview();
              }),
          ui.actionLayout(44, 48));
      list.addView(row);
    }
  }
}
