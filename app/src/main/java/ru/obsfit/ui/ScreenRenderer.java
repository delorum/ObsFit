package ru.obsfit.ui;

import java.util.*;
import java.util.function.ToLongFunction;
import ru.obsfit.*;

/** Chooses the screen and toolbar from typed navigation state. */
public final class ScreenRenderer {
  private final Ui ui;
  private final AppController controller;
  private final AppState state;
  private final ScreenHost actions;
  private final ToLongFunction<VaultMarkdown.Food> recent;
  private FoodHistoryScreen historyScreen;

  public ScreenRenderer(
      Ui ui,
      AppController controller,
      ScreenHost actions,
      ToLongFunction<VaultMarkdown.Food> recent) {
    this.ui = ui;
    this.controller = controller;
    state = controller.state;
    this.actions = actions;
    this.recent = recent;
  }

  public void render() {
    List<Ui.Action> toolbar = new ArrayList<>();
    String title, subtitle;
    Runnable back =
        state.screen == AppState.Screen.DAY
                || state.screen == AppState.Screen.PORTION
                || state.screen == AppState.Screen.SETTINGS && !actions.connected()
            ? null
            : actions::back;
    switch (state.screen) {
      case FOOD_HISTORY -> {
        title = "История";
        subtitle = state.foodHistory.food.name;
      }
      case DAY -> {
        title = "ObsFit";
        subtitle = "Дневник питания";
        toolbar.add(new Ui.Action("≡", "Справочник еды", actions::openCatalog));
        toolbar.add(new Ui.Action("↻", "Обновить данные из хранилища", actions::refreshDay));
        toolbar.add(new Ui.Action("⚙", "Настройки", actions::openSettings));
      }
      case CALCULATOR -> {
        title = "Калькулятор";
        subtitle = "Вычислить и перенести в поле";
      }
      case PORTION -> {
        title = state.portion.entry == null ? "Добавить еду" : "Редактировать";
        subtitle = state.portion.section + " · " + state.selected;
      }
      case CATALOG -> {
        title = "Справочник";
        subtitle = "Еда.md";
        toolbar.add(new Ui.Action("↻", "Обновить справочник", actions::openCatalog));
      }
      case CATALOG_EDITOR -> {
        title =
            state.catalogForm == null || state.catalogForm.food == null
                ? "Новый продукт"
                : "Изменить продукт";
        subtitle = "Справочник еды";
        toolbar.add(
            new Ui.Action(
                "▣",
                "Сканировать штрихкод. Удержание — ввод вручную.",
                () -> actions.scan(AppState.ScanTarget.CATALOG),
                () -> actions.manualBarcode(AppState.ScanTarget.CATALOG)));
      }
      default -> {
        title = "Настройки";
        subtitle = "ObsFit · локальное хранилище";
      }
    }
    ui.page(
        title,
        subtitle,
        back,
        toolbar,
        state.refreshing
            || state.screen == AppState.Screen.FOOD_HISTORY && state.foodHistory.loading);
    switch (state.screen) {
      case FOOD_HISTORY -> {
        historyScreen = new FoodHistoryScreen(ui, state.foodHistory, actions);
        historyScreen.render();
      }
      case CALCULATOR -> new CalculatorScreen(ui, state.calculator, actions).render();
      case DAY -> new DayScreen(ui, controller, actions).render();
      case PORTION -> new PortionScreen(ui, state, actions, recent).render();
      case CATALOG -> {
        if (state.catalog != null) new CatalogScreen(ui, state, actions).render();
      }
      case CATALOG_EDITOR -> {
        if (state.catalogForm != null) new CatalogEditorScreen(ui, state, actions, recent).render();
      }
      case SETTINGS -> SettingsScreen.render(ui, actions);
    }
    ui.error(state.error);
  }

  public void updateFoodHistory() {
    if (historyScreen != null && state.screen == AppState.Screen.FOOD_HISTORY)
      historyScreen.update();
  }
}
