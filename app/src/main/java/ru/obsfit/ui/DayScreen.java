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
  public interface Actions extends HealthSections.Actions {
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
  private Map<String, MealPlan.SectionBudget> mealBudgets = Map.of();

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
    ui.navigationButtons(prev, next, controller::canNavigate);
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
    double limit = state.day.day.dailyLimit;
    int color = total > limit ? RED : total >= limit - 100 ? YELLOW : GREEN;
    LinearLayout calories = ui.column();
    calories.setBackground(ui.background(CARD, 22));
    calories.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(20));
    calories.addView(ui.text("СЪЕДЕНО ЗА ДЕНЬ", 11, MUTED));
    calories.addView(
        ui.bold(VaultMarkdown.fmt(total) + " / " + VaultMarkdown.fmt(limit) + " ккал", 30, color));
    LinearProgressIndicator progress = new LinearProgressIndicator(ui.activity);
    progress.setMax(Math.max(1, (int) Math.round(limit)));
    progress.setProgress((int) Math.min(total, limit));
    progress.setIndicatorColor(color);
    progress.setTrackColor(0xff3b4350);
    progress.setTrackCornerRadius(ui.dp(4));
    calories.addView(progress, new LinearLayout.LayoutParams(-1, ui.dp(8)));
    ui.clockHint(
        calories, () -> MealPlan.hint(state.day.note.date, LocalDateTime.now(), state.day.day));
    ui.body.addView(calories);
    ui.gap(ui.body, 20);
    ui.onClock(
        () ->
            mealBudgets =
                MealPlan.sectionBudgets(state.day.note.date, LocalDateTime.now(), state.day.day));
    for (String section : VaultMarkdown.SECTIONS) if (!section.equals("Заметки")) section(section);
    section("Заметки");
    new HealthSections(ui, state, actions).render();
    ui.body.addView(ui.text("Листайте существующие дни · ↻ обновляет открытый день", 12, MUTED));
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
    sectionCalories.setGravity(Gravity.END);
    if (!name.equals("Заметки"))
      ui.onClock(
          () -> {
            var budget = mealBudgets.get(name);
            String labelText = budget.label();
            if (!labelText.contentEquals(sectionCalories.getText()))
              sectionCalories.setText(labelText);
            sectionCalories.setTextColor(budget.remaining() < 0 ? RED : ACCENT);
            boolean highlighted =
                name.equals(MealPlan.highlightedSection(state.selected, LocalDateTime.now()));
            sectionCalories.setTypeface(
                null,
                highlighted ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            sectionCalories.setContentDescription(labelText.replace("\n", ", "));
            heading.setContentDescription(
                name
                    + ", "
                    + labelText.replace("\n", ", ")
                    + ", "
                    + (state.expanded.contains(name) ? "свернуть" : "раскрыть"));
          });
    heading.addView(sectionCalories);
    heading.addView(
        ui.icon("＋", "Добавить еду: " + name, () -> actions.portion(name, null)),
        ui.actionLayout(48, 48));
    heading.setMinimumHeight(ui.dp(48));
    if (name.equals("Заметки"))
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
      foodRows(card, entries, name, -1);
    }
    ui.body.addView(card);
    ui.gap(ui.body, 10);
  }

  private void foodRows(
      LinearLayout parent, List<VaultMarkdown.Entry> entries, String section, int parentLine) {
    List<RecipeBlocks.Group> groups = new ArrayList<>();
    for (var group : state.day.day.recipes)
      if (group.section().equals(section)
          && group.parentLine() == parentLine
          && !group.entries().isEmpty()) groups.add(group);
    Map<Integer, View> rows = new TreeMap<>();
    for (var item : entries) {
      boolean nested =
          groups.stream().anyMatch(group -> item.line > group.line() && item.line < group.end());
      if (!nested) rows.put(item.line, entry(item));
    }
    for (var group : groups) rows.put(group.line(), recipe(group));
    for (var view : rows.values()) parent.addView(view);
  }

  private View recipe(RecipeBlocks.Group group) {
    String key = "recipe:" + state.day.note.path + ":" + group.line();
    boolean expanded = state.expanded.contains(key);
    LinearLayout wrapper = ui.column();
    LinearLayout heading = ui.row();
    heading.setMinimumHeight(ui.dp(48));
    ui.weighted(heading, ui.bold((expanded ? "⌄ " : "› ") + group.name(), 16, INK));
    heading.addView(ui.text(VaultMarkdown.fmt(group.calories()) + " ккал", 14, ACCENT));
    heading.setContentDescription(
        group.name()
            + ", "
            + VaultMarkdown.fmt(group.calories())
            + " ккал, "
            + (expanded ? "свернуть" : "раскрыть ингредиенты"));
    heading.setOnClickListener(
        v -> {
          state.scrollY = ui.scroll.getScrollY();
          if (!state.expanded.add(key)) state.expanded.remove(key);
          actions.redraw();
        });
    wrapper.addView(heading);
    if (expanded) {
      LinearLayout children = ui.column();
      children.setPadding(ui.dp(10), 0, 0, 0);
      foodRows(children, group.entries(), group.section(), group.line());
      wrapper.addView(children);
    }
    return wrapper;
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
