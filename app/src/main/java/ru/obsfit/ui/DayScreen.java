package ru.obsfit.ui;

import static ru.obsfit.ui.Ui.*;

import android.view.*;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import ru.obsfit.*;

public final class DayScreen {
  public interface Actions {
    void portion(String section, VaultMarkdown.Entry entry);

    void delete(VaultMarkdown.Entry entry);

    void move(VaultMarkdown.Entry entry);

    void entryMenu(VaultMarkdown.Entry entry);

    void navigate(int delta);

    void chooseDate();

    void redraw();
  }

  private final Ui ui;
  private final AppController controller;
  private final AppState state;
  private final Actions actions;

  public DayScreen(Ui ui, AppController controller, Actions actions) {
    this.ui = ui;
    this.controller = controller;
    state = controller.state;
    this.actions = actions;
  }

  public void render() {
    if (state.notes.isEmpty()) {
      ui.body.addView(ui.bold("Нет ежедневных заметок", 24, INK));
      ui.body.addView(
          ui.text(
              "В Дневник/Хроника пока нет заметок с датами. Создайте день в Obsidian и нажмите ↻.",
              16,
              MUTED));
      return;
    }
    if (state.day == null) {
      ui.body.addView(ui.text("Загружаем день…", 18, MUTED));
      return;
    }
    LinearLayout dates = ui.row();
    MaterialButton prev = ui.icon("‹", "Предыдущая заметка", () -> actions.navigate(-1)),
        next = ui.icon("›", "Следующая заметка", () -> actions.navigate(1));
    prev.setEnabled(controller.canNavigate(-1));
    next.setEnabled(controller.canNavigate(1));
    prev.setAlpha(prev.isEnabled() ? 1f : .28f);
    next.setAlpha(next.isEnabled() ? 1f : .28f);
    var prevParams = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(52));
    dates.addView(prev, prevParams);
    LinearLayout label = ui.column();
    TextView date =
        ui.bold(
            state.selected.format(
                DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))),
            26,
            INK);
    date.setGravity(Gravity.CENTER);
    label.addView(date);
    TextView week =
        ui.text(
            state.selected.format(
                DateTimeFormatter.ofPattern("EEEE · yyyy", Locale.forLanguageTag("ru"))),
            13,
            MUTED);
    week.setGravity(Gravity.CENTER);
    label.addView(week);
    label.setContentDescription("Выбрать дату");
    label.setOnClickListener(v -> actions.chooseDate());
    ui.weighted(dates, label);
    dates.addView(next, ui.actionLayout(48, 52));
    ui.body.addView(dates);
    ui.gap(ui.body, 20);
    double total = state.day.day.total;
    int color = total > 2600 ? RED : total >= 2500 ? YELLOW : GREEN;
    LinearLayout calories = ui.column();
    calories.setBackground(ui.background(CARD, 22));
    calories.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(20));
    calories.addView(ui.text("СЪЕДЕНО ЗА ДЕНЬ", 11, MUTED));
    calories.addView(ui.bold(VaultMarkdown.fmt(total) + " / 2600 ккал", 30, color));
    LinearProgressIndicator progress = new LinearProgressIndicator(ui.activity);
    progress.setMax(2600);
    progress.setProgress((int) Math.min(total, 2600));
    progress.setIndicatorColor(color);
    progress.setTrackColor(0xff3b4350);
    progress.setTrackCornerRadius(ui.dp(4));
    calories.addView(progress, new LinearLayout.LayoutParams(-1, ui.dp(8)));
    ui.body.addView(calories);
    ui.gap(ui.body, 20);
    for (String section : VaultMarkdown.SECTIONS) section(section);
    ui.body.addView(
        ui.text("Листайте только существующие дни · ↻ перечитывает хранилище", 12, MUTED));
    ui.scrollTo(state.scrollY);
  }

  private void section(String name) {
    var entries = state.day.day.section(name);
    double total = entries.stream().mapToDouble(e -> e.kcal).sum();
    LinearLayout card = ui.column();
    card.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(12));
    card.setBackground(ui.background(CARD, 20));
    LinearLayout heading = ui.row();
    ui.weighted(heading, ui.bold((state.expanded.contains(name) ? "⌄  " : "›  ") + name, 18, INK));
    TextView sectionCalories = ui.text(VaultMarkdown.fmt(total) + " ккал", 14, ACCENT);
    heading.addView(sectionCalories);
    heading.addView(
        ui.icon("＋", "Добавить еду: " + name, () -> actions.portion(name, null)),
        ui.actionLayout(48, 48));
    heading.setMinimumHeight(ui.dp(48));
    heading.setContentDescription(
        name
            + ", "
            + VaultMarkdown.fmt(total)
            + " килокалорий, "
            + (state.expanded.contains(name) ? "свернуть" : "раскрыть"));
    heading.setOnClickListener(
        v -> {
          state.scrollY = ui.scroll.getScrollY();
          if (!state.expanded.add(name)) state.expanded.remove(name);
          actions.redraw();
        });
    card.addView(heading);
    if (state.expanded.contains(name)) {
      if (entries.isEmpty()) card.addView(ui.text("Пока ничего", 14, MUTED));
      for (var entry : entries) card.addView(entry(entry));
    }
    ui.body.addView(card);
    ui.gap(ui.body, 10);
  }

  private View entry(VaultMarkdown.Entry entry) {
    LinearLayout wrapper = ui.column();
    Ui.ClickRow row = ui.clickRow();
    row.setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(12));
    LinearLayout labels = ui.column();
    labels.addView(ui.text(entry.name.isBlank() ? "Калории" : entry.name, 17, INK));
    labels.addView(
        ui.text(
            entry.weight == null ? "Без веса" : VaultMarkdown.fmt(entry.weight) + " г", 13, MUTED));
    ui.weighted(row, labels);
    row.addView(ui.bold(VaultMarkdown.fmt(entry.kcal) + " ккал", 16, INK));
    row.addView(
        ui.icon("⋮", "Действия с записью", () -> actions.entryMenu(entry)),
        ui.actionLayout(40, 48));
    row.setContentDescription(
        (entry.name.isBlank() ? "Калории" : entry.name)
            + ", "
            + VaultMarkdown.fmt(entry.kcal)
            + " ккал. Нажмите для редактирования; смахните влево для удаления, вправо для"
            + " переноса.");
    Swipes.bind(ui, row, () -> actions.delete(entry), () -> actions.move(entry));
    row.setOnClickListener(v -> actions.portion(entry.section, entry));
    row.setOnLongClickListener(
        v -> {
          actions.entryMenu(entry);
          return true;
        });
    wrapper.addView(row);
    View divider = new View(ui.activity);
    divider.setBackgroundColor(0xff383f4a);
    wrapper.addView(divider, new LinearLayout.LayoutParams(-1, ui.dp(1)));
    return wrapper;
  }
}
