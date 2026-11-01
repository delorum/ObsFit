package ru.obsfit.ui;

import static ru.obsfit.ui.Ui.*;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.util.function.ToLongFunction;
import ru.obsfit.*;

public final class PortionScreen {
  public interface Actions {
    void back();

    void redraw();

    void hideKeyboard();

    void savePortion();

    void chooseFood(VaultMarkdown.Food food);

    void newFood();

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

  public PortionScreen(
      Ui ui, AppState state, Actions actions, ToLongFunction<VaultMarkdown.Food> recent) {
    this.ui = ui;
    this.state = state;
    form = state.portion;
    this.actions = actions;
    this.recent = recent;
  }

  public void render() {
    ui.body.addView(ui.button("‹  Назад к дню", actions::back));
    ui.gap(ui.body, 12);
    LinearLayout mode = ui.row();
    ui.weighted(
        mode,
        ui.button(
            "Из справочника",
            () -> {
              actions.chooseFromCatalog();
            }));
    ui.weighted(
        mode,
        ui.button(
            "＋ Новая еда",
            () -> {
              actions.newFood();
            }));
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
            LinearLayout card = ui.column();
            card.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
            card.setBackground(ui.background(CARD, 16));
            card.addView(ui.bold(food.name, 17, INK));
            card.addView(
                ui.text(
                    VaultMarkdown.fmt(food.weight)
                        + " г · "
                        + VaultMarkdown.fmt(food.kcal100)
                        + " ккал/100 г",
                    13,
                    MUTED));
            card.setOnClickListener(
                v -> {
                  actions.chooseFood(food);
                });
            list.addView(card);
            ui.gap(list, 8);
          }
          if (foods.isEmpty())
            list.addView(ui.text("Еда не найдена. Можно добавить новую запись.", 16, MUTED));
          caption.setText(
              foods.size() > 100
                  ? "Показаны первые 100 из " + foods.size() + ". Уточните поиск."
                  : form.query.isBlank()
                      ? "Недавно добавленные — в начале списка"
                      : "Сначала точные совпадения, затем похожие");
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
    EditText name = ui.field(nameBox, "Название еды", form.name, false, form.selectedFood == null);
    ui.watch(
        name,
        value -> {
          form.name = value;
          form.dirty = true;
        });
    if (form.selectedFood != null)
      nameRow.addView(
          ui.icon("✎", "Редактировать продукт в справочнике", actions::editSelectedFood),
          new LinearLayout.LayoutParams(ui.dp(48), ui.dp(52)));
    ui.body.addView(nameRow);
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
    ui.gap(ui.body, 10);
    if (form.newFood) {
      EditText defaultWeight =
          ui.field(ui.body, "Вес по умолчанию, г", form.defaultWeight, true, true);
      ui.watch(
          defaultWeight,
          value -> {
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
      EditText kcal = ui.field(ui.body, "Калории, ккал", form.manualKcal, true, true);
      ui.watch(
          kcal,
          value -> {
            form.manualKcal = value;
            form.dirty = true;
            update.run();
          });
    } else {
      EditText weight = ui.field(ui.body, "Вес порции, г", form.weight, true, true);
      EditText kcal =
          ui.field(
              ui.body,
              "Калорийность на 100 г",
              form.kcal100,
              true,
              form.newFood || form.selectedFood == null);
      ui.watch(
          weight,
          value -> {
            form.weight = value;
            form.dirty = true;
            update.run();
          });
      ui.watch(
          kcal,
          value -> {
            form.kcal100 = value;
            form.dirty = true;
            update.run();
          });
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
