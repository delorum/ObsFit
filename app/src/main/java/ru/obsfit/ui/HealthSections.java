package ru.obsfit.ui;

import static ru.obsfit.ui.Ui.*;

import android.widget.*;
import ru.obsfit.*;

/** Compact day cards for recorded approaches and measured body weight. */
public final class HealthSections {
  public interface Actions {
    String workoutPreset(SportsDiary.Exercise exercise);

    void workoutSet(SportsDiary.Exercise exercise, int index, SportsDiary.Set set);

    void removeWorkoutSet(SportsDiary.Exercise exercise, int index);

    void editWorkoutPreset(SportsDiary.Exercise exercise);

    void editWeight();

    void removeWeight();

    void redraw();
  }

  private final Ui ui;
  private final AppState state;
  private final Actions actions;

  public HealthSections(Ui ui, AppState state, Actions actions) {
    this.ui = ui;
    this.state = state;
    this.actions = actions;
  }

  private LinearLayout card(String name) {
    LinearLayout card = ui.column();
    card.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(12));
    card.setBackground(ui.background(CARD, 20));
    var heading = ui.clickRow();
    heading.setMinimumHeight(ui.dp(48));
    ui.weighted(heading, ui.bold((state.expanded.contains(name) ? "⌄  " : "›  ") + name, 18, INK));
    heading.setContentDescription(
        name + (state.expanded.contains(name) ? ", свернуть" : ", раскрыть"));
    heading.setOnClickListener(
        v -> {
          state.scrollY = ui.scroll.getScrollY();
          if (!state.expanded.add(name)) state.expanded.remove(name);
          actions.redraw();
        });
    card.addView(heading);
    ui.body.addView(card);
    ui.gap(ui.body, 10);
    return card;
  }

  public void render() {
    LinearLayout sports = card("Тренировка");
    if (state.expanded.contains("Тренировка"))
      for (var e : SportsDiary.EXERCISES) {
        var header = ui.row();
        ui.weighted(header, ui.bold(e.name(), 16, INK));
        header.addView(
            ui.icon("＋", "Добавить подход: " + e.name(), () -> actions.workoutSet(e, -1, null)),
            ui.actionLayout(48, 48));
        sports.addView(header);
        try {
          var sets = SportsDiary.parse(e, SportsDiary.read(state.day.day.source, e.key()));
          if (sets.isEmpty()) sports.addView(ui.text("—", 14, MUTED));
          for (int i = 0; i < sets.size(); i++) {
            final int index = i;
            final var set = sets.get(i);
            var row = ui.clickRow();
            row.setMinimumHeight(ui.dp(48));
            ui.weighted(
                row, ui.text((i + 1) + ".  " + set.format(e) + (e.bike() ? "" : " раз"), 16, INK));
            row.setContentDescription(
                e.name()
                    + ", подход "
                    + (i + 1)
                    + ", "
                    + set.format(e)
                    + ". Нажмите для изменения, смахните для удаления.");
            row.setOnClickListener(v -> actions.workoutSet(e, index, set));
            Swipes.bind(
                ui,
                row,
                () -> actions.removeWorkoutSet(e, index),
                () -> actions.removeWorkoutSet(e, index));
            sports.addView(row);
          }
        } catch (IllegalArgumentException error) {
          sports.addView(ui.text(error.getMessage() + " · проверьте запись в Obsidian", 14, RED));
        }
      }
    LinearLayout weight = card("Вес");
    if (state.expanded.contains("Вес")) {
      String value;
      try {
        value = SportsDiary.read(state.day.day.source, "Вес");
      } catch (IllegalArgumentException error) {
        weight.addView(ui.text(error.getMessage(), 14, RED));
        return;
      }
      final String raw = value;
      var row = ui.clickRow();
      row.setMinimumHeight(ui.dp(48));
      ui.weighted(row, ui.text(raw.isBlank() ? "—" : raw + " кг", 20, INK));
      row.setContentDescription(
          "Вес за " + state.selected + ". Нажмите для изменения, смахните для удаления.");
      row.setOnClickListener(v -> actions.editWeight());
      if (!raw.isBlank()) Swipes.bind(ui, row, actions::removeWeight, actions::removeWeight);
      weight.addView(row);
    }
  }
}
