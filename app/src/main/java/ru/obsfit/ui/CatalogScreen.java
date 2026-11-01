package ru.obsfit.ui;

import static ru.obsfit.ui.Ui.*;

import android.view.View;
import android.widget.*;
import ru.obsfit.*;

public final class CatalogScreen {
  public interface Actions {
    void editCatalog(VaultMarkdown.Food food);

    void deleteCatalog(VaultMarkdown.Food food);

    void catalogMenu(VaultMarkdown.Food food);
  }

  private final Ui ui;
  private final AppState state;
  private final Actions actions;

  public CatalogScreen(Ui ui, AppState state, Actions actions) {
    this.ui = ui;
    this.state = state;
    this.actions = actions;
  }

  public void render() {
    ui.body.addView(ui.button("＋ Новая запись", () -> actions.editCatalog(null)));
    ui.gap(ui.body, 12);
    EditText query = ui.field(ui.body, "Найти название или алиас", state.catalogQuery, false, true);
    LinearLayout list = ui.column();
    ui.body.addView(list);
    Runnable fill =
        () -> {
          list.removeAllViews();
          var foods = FoodSearch.find(state.catalog.catalog.foods, state.catalogQuery, f -> 0);
          int shown = 0;
          if (foods.isEmpty())
            list.addView(
                ui.text(
                    state.catalog.catalog.foods.isEmpty()
                        ? "Справочник пуст. Добавьте первую запись."
                        : "Ничего не найдено",
                    16,
                    MUTED));
          for (var food : foods) {
            if (shown++ >= 100) break;
            list.addView(row(food));
            ui.gap(list, 10);
          }
          if (foods.size() > 100)
            list.addView(ui.text("Показаны первые 100 записей. Уточните поиск.", 13, MUTED));
        };
    ui.watch(
        query,
        value -> {
          state.catalogQuery = value;
          fill.run();
        });
    fill.run();
  }

  private View row(VaultMarkdown.Food food) {
    LinearLayout card = ui.column();
    card.setBackground(ui.background(CARD, 18));
    card.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
    Ui.ClickRow row = ui.clickRow();
    LinearLayout labels = ui.column();
    labels.addView(ui.bold(food.name, 18, INK));
    labels.addView(
        ui.text(
            VaultMarkdown.fmt(food.kcal100)
                + " ккал/100 г · "
                + VaultMarkdown.fmt(food.weight)
                + " г",
            13,
            MUTED));
    if (!food.aliases.isEmpty())
      labels.addView(ui.text(String.join(", ", food.aliases), 12, MUTED));
    if (!food.ingredients.isEmpty()) labels.addView(ui.text("Составное блюдо", 12, ACCENT));
    ui.weighted(row, labels);
    row.addView(
        ui.icon("⋮", "Действия с продуктом", () -> actions.catalogMenu(food)),
        ui.actionLayout(44, 48));
    row.setContentDescription(
        food.name + ". Нажмите для редактирования или смахните для удаления.");
    Swipes.bind(ui, row, () -> actions.deleteCatalog(food), () -> actions.deleteCatalog(food));
    row.setOnClickListener(v -> actions.editCatalog(food));
    card.addView(row);
    return card;
  }
}
