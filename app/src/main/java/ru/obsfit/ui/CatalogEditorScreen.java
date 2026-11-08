package ru.obsfit.ui;

import static ru.obsfit.ui.Ui.*;

import android.content.res.ColorStateList;
import android.widget.*;
import java.util.function.ToLongFunction;
import ru.obsfit.*;

public final class CatalogEditorScreen {
  public interface Actions extends CalculatorActions {
    void saveCatalog();
  }

  private final Ui ui;
  private final AppState state;
  private final CatalogForm form;
  private final Actions actions;
  private final IngredientSection composition;
  private EditText weight, kcal;
  private TextView calculation;
  private boolean refreshing;

  public CatalogEditorScreen(
      Ui ui, AppState state, Actions actions, ToLongFunction<VaultMarkdown.Food> recent) {
    this.ui = ui;
    this.state = state;
    form = state.catalogForm;
    this.actions = actions;
    composition = new IngredientSection(ui, state, form, this::preview, recent, true, actions);
  }

  public void render() {
    EditText name = ui.field(ui.body, "Название", form.name, false, true);
    EditText aliases = ui.field(ui.body, "Алиасы: по одному на строке", form.aliases, false, true);
    ui.multiline(aliases, 2);
    weight =
        ui.calculatedField(
            ui.body,
            "Вес по умолчанию, г",
            form.weight,
            !form.recipe(),
            value -> actions.calculator(CalculatorForm.Target.CATALOG_WEIGHT));
    kcal =
        ui.calculatedField(
            ui.body,
            "Калорийность на 100 г",
            form.kcal100,
            !form.recipe(),
            value -> actions.calculator(CalculatorForm.Target.CATALOG_KCAL100));
    composition.render(ui.body);
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
    preview();
  }

  private void preview() {
    CatalogForm.Preview result = form.preview();
    refreshing = true;
    ui.setFieldEnabled(weight, !form.recipe());
    ui.setFieldEnabled(kcal, !form.recipe());
    if (!weight.getText().toString().equals(form.weight)) weight.setText(form.weight);
    if (!kcal.getText().toString().equals(form.kcal100)) kcal.setText(form.kcal100);
    refreshing = false;
    calculation.setText(
        result.error() == null
            ? "Порция по умолчанию: " + VaultMarkdown.fmt(result.portionCalories()) + " ккал"
            : result.error());
    calculation.setTextColor(result.error() == null ? ACCENT : MUTED);
  }
}
