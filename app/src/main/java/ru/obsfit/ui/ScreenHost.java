package ru.obsfit.ui;

/** Commands exposed by the Android coordinator; screens cannot access repositories or storage. */
public interface ScreenHost
    extends DayScreen.Actions,
        PortionScreen.Actions,
        CatalogScreen.Actions,
        CatalogEditorScreen.Actions,
        SettingsScreen.Actions,
        CalculatorScreen.Actions,
        FoodHistoryActions {
  void openCatalog();

  void refreshDay();

  void openSettings();
}
