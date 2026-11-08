package ru.obsfit.ui;

import static ru.obsfit.ui.Ui.*;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.util.function.ToLongFunction;
import ru.obsfit.*;

public final class PortionScreen {
  public interface Actions extends CalculatorActions {
    void back();

    void redraw();

    void hideKeyboard();

    void savePortion();

    void chooseFood(VaultMarkdown.Food food);

    void quickAddFood(VaultMarkdown.Food food);

    void newFood();

    void manualCalories();

    void chooseFromCatalog();

    void editSelectedFood();

    void scan(AppState.ScanTarget target);

    void manualBarcode(AppState.ScanTarget target);
  }

  private final Ui ui;
  private final AppState state;
  private final PortionForm form;
  private final Actions actions;
  private final ToLongFunction<VaultMarkdown.Food> recent;
  private EditText defaultWeightField, kcalField, portionWeightField;
  private boolean refreshing;
  private IngredientSection ingredientSection;

  private void updateRecipeFields() {
    form.recalculateRecipe();
    refreshing = true;
    if (defaultWeightField != null) {
      ui.setFieldEnabled(defaultWeightField, !form.recipe());
      if (!defaultWeightField.getText().toString().equals(form.defaultWeight))
        defaultWeightField.setText(form.defaultWeight);
    }
    if (kcalField != null) {
      ui.setFieldEnabled(kcalField, !form.recipe() && (form.newFood || form.selectedFood == null));
      if (!kcalField.getText().toString().equals(form.kcal100)) kcalField.setText(form.kcal100);
    }
    if (portionWeightField != null && !portionWeightField.getText().toString().equals(form.weight))
      portionWeightField.setText(form.weight);
    refreshing = false;
  }

  public PortionScreen(
      Ui ui, AppState state, Actions actions, ToLongFunction<VaultMarkdown.Food> recent) {
    this.ui = ui;
    this.state = state;
    form = state.portion;
    this.actions = actions;
    this.recent = recent;
  }

  public void render() {
    boolean backToList = !form.choosing && form.entry == null && form.selectedFood != null;
    ui.body.addView(
        ui.button(backToList ? "‹  Список продуктов" : "‹  Назад к дню", actions::back));
    ui.gap(ui.body, 12);
    LinearLayout mode = ui.row();
    if (form.entry == null) {
      ui.weighted(
          mode,
          ui.button(
              form.choosing ? "＋ Новая еда" : "Из справочника",
              () -> {
                if (form.choosing) actions.newFood();
                else actions.chooseFromCatalog();
              }));
      if (form.manual && !form.choosing)
        ui.weighted(mode, ui.button("＋ Новая еда", actions::newFood));
      else ui.weighted(mode, ui.button("＋ ккал", actions::manualCalories));
    } else {
      ui.weighted(mode, ui.button("Из справочника", actions::chooseFromCatalog));
      ui.weighted(mode, ui.button("＋ Новая еда", actions::newFood));
    }
    ui.body.addView(mode);
    ui.gap(ui.body, 12);
    if (form.choosing) choices();
    else fields();
  }

  private void choices() {
    EditText search = ui.field(ui.body, "Название, алиас или часть слова", form.query, false, true);
    LinearLayout list = ui.column();
    ui.body.addView(list);
    TextView caption = ui.text("", 12, MUTED);
    ui.body.addView(caption);
    Runnable fill =
        () -> {
          list.removeAllViews();
          var foods = FoodSearch.find(form.original.catalog.foods, form.query, recent);
          int shown = 0;
          for (var food : foods) {
            if (shown++ >= 100) break;
            Ui.ClickRow card = ui.clickRow();
            LinearLayout labels = ui.column();
            card.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
            card.setBackground(ui.background(CARD, 16));
            labels.addView(ui.bold(FoodLabels.title(food), 17, INK));
            labels.addView(ui.text(FoodLabels.details(form.original.catalog, food), 13, MUTED));
            ui.weighted(card, labels);
            if (form.entry == null) {
              Swipes.bind(
                  ui, card, () -> actions.quickAddFood(food), () -> actions.quickAddFood(food));
              card.setContentDescription(
                  food.name
                      + (food.ingredients.isEmpty() ? "" : ". Составное блюдо")
                      + ". Нажмите, чтобы изменить вес, или смахните, чтобы добавить стандартную"
                      + " порцию.");
            }
            card.setOnClickListener(
                v -> {
                  actions.chooseFood(food);
                });
            list.addView(card);
            ui.gap(list, 8);
          }
          if (foods.isEmpty())
            list.addView(ui.text("Еда не найдена. Можно добавить новую запись.", 16, MUTED));
          String text =
              foods.size() > 100
                  ? "Показаны первые 100 из " + foods.size() + ". Уточните поиск."
                  : form.query.isBlank()
                      ? "Недавно добавленные — в начале списка"
                      : "Сначала точные совпадения, затем похожие";
          caption.setText(
              text + " · ⊞ — состав" + (form.entry == null ? " · Свайп — добавить" : ""));
        };
    ui.watch(
        search,
        value -> {
          form.query = value;
          fill.run();
        });
    fill.run();
  }

  private void fields() {
    form.recalculateRecipe();
    if (form.newFood) {
      ui.body.addView(
          ui.button("▣  Сканировать штрихкод", () -> actions.scan(AppState.ScanTarget.PORTION)));
      ui.body.addView(
          ui.button(
              "Ввести штрихкод вручную", () -> actions.manualBarcode(AppState.ScanTarget.PORTION)));
      ui.gap(ui.body, 12);
    }
    LinearLayout nameRow = ui.row(), nameBox = ui.column();
    ui.weighted(nameRow, nameBox);
    EditText name =
        ui.field(
            nameBox,
            form.manual ? "Описание (необязательно)" : "Название еды",
            form.name,
            false,
            form.selectedFood == null);
    ui.watch(
        name,
        value -> {
          form.name = value;
          form.dirty = true;
        });
    if (form.selectedFood != null)
      nameRow.addView(
          ui.icon("✎", "Редактировать продукт в справочнике", actions::editSelectedFood),
          ui.actionLayout(48, 52));
    ui.body.addView(nameRow);
    if (form.newFood) {
      LinearLayout aliases = ui.column();
      aliases.setVisibility(form.aliasesExpanded ? View.VISIBLE : View.GONE);
      ui.body.addView(
          ui.button(
              "Алиасы  ⌄",
              () -> {
                form.aliasesExpanded = !form.aliasesExpanded;
                aliases.setVisibility(form.aliasesExpanded ? View.VISIBLE : View.GONE);
              }));
      EditText alias =
          ui.field(aliases, "Алиасы: по одному на строке", form.aliases, false, form.newFood);
      ui.multiline(alias, 2);
      ui.watch(
          alias,
          value -> {
            form.aliases = value;
            form.dirty = true;
          });
      ui.body.addView(aliases);
    }
    ui.gap(ui.body, 10);
    if (form.newFood) {
      defaultWeightField =
          ui.calculatedField(
              ui.body,
              "Вес по умолчанию, г",
              form.defaultWeight,
              !form.recipe(),
              value -> actions.calculator(CalculatorForm.Target.PORTION_DEFAULT_WEIGHT));
      ui.watch(
          defaultWeightField,
          value -> {
            if (refreshing) return;
            form.defaultWeight = value;
            form.dirty = true;
          });
    }
    TextView total = ui.bold("", 27, GREEN);
    Runnable update =
        () -> {
          try {
            total.setText(
                ui.activity.getString(R.string.portion_calories, VaultMarkdown.fmt(form.total())));
          } catch (IllegalArgumentException error) {
            total.setText("— ккал в порции");
          }
        };
    if (form.manual) {
      EditText kcal =
          ui.calculatedField(
              ui.body,
              "Калории, ккал",
              form.manualKcal,
              true,
              value -> actions.calculator(CalculatorForm.Target.PORTION_CALORIES));
      ui.watch(
          kcal,
          value -> {
            form.manualKcal = value;
            form.dirty = true;
            update.run();
          });
    } else {
      portionWeightField =
          ui.calculatedField(
              ui.body,
              "Вес порции, г",
              form.weight,
              true,
              value -> actions.calculator(CalculatorForm.Target.PORTION_WEIGHT));
      kcalField =
          ui.calculatedField(
              ui.body,
              "Калорийность на 100 г",
              form.kcal100,
              !form.recipe() && (form.newFood || form.selectedFood == null),
              value -> actions.calculator(CalculatorForm.Target.PORTION_KCAL100));
      ui.watch(
          portionWeightField,
          value -> {
            if (refreshing) return;
            form.changePortionWeight(value);
            if (form.selectedRecipe()) {
              updateRecipeFields();
              if (ingredientSection != null) ingredientSection.refresh();
            }
            update.run();
          });
      ui.watch(
          kcalField,
          value -> {
            if (refreshing) return;
            form.kcal100 = value;
            form.dirty = true;
            update.run();
          });
    }
    if (form.newFood || form.selectedRecipe()) {
      ingredientSection =
          new IngredientSection(
              ui,
              state,
              form.composition,
              () -> {
                form.compositionChanged();
                updateRecipeFields();
                update.run();
              },
              recent,
              form.newFood,
              actions);
      ingredientSection.render(ui.body);
      updateRecipeFields();
    }
    ui.body.addView(total);
    ui.gap(ui.body, 12);
    update.run();
    var save =
        ui.button(
            form.entry == null ? "Добавить в " + form.section : "Сохранить изменения",
            actions::savePortion);
    save.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
    save.setTextColor(BG);
    ui.body.addView(save, new LinearLayout.LayoutParams(-1, ui.dp(56)));
    if (form.newFood)
      ui.body.addView(
          ui.text(
              "Новый продукт сохранится в таблицу Еда.md и сразу добавится в выбранный раздел"
                  + " дневника.",
              13,
              MUTED));
    if (form.entry != null && form.selectedFood == null && !form.newFood)
      ui.body.addView(
          ui.text(
              "Эта запись не найдена в справочнике. Изменения сохранятся только в дневной заметке.",
              13,
              MUTED));
  }
}
