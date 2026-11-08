package ru.obsfit.ui;

import static ru.obsfit.ui.Ui.*;

import android.view.View;
import android.widget.*;
import java.time.format.DateTimeFormatter;
import ru.obsfit.*;

/** Appends new rows without replacing the ScrollView or moving its existing position. */
public final class FoodHistoryScreen {
  private final Ui ui;
  private final FoodHistory.Form form;
  private final FoodHistoryActions actions;
  private LinearLayout list;
  private TextView footer;
  private View retry;
  private int rendered;

  public FoodHistoryScreen(Ui ui, FoodHistory.Form form, FoodHistoryActions actions) {
    this.ui = ui;
    this.form = form;
    this.actions = actions;
  }

  public void render() {
    if (!form.food.ingredients.isEmpty())
      ui.body.addView(
          ui.text("Если блюдо разложено, его история доступна по ингредиентам.", 13, MUTED));
    list = ui.column();
    ui.body.addView(list);
    footer = ui.text("", 14, MUTED);
    ui.body.addView(footer);
    retry = ui.button("Повторить загрузку", actions::moreFoodHistory);
    ui.body.addView(retry);
    ui.scroll.setOnScrollChangeListener(
        (View.OnScrollChangeListener)
            (view, x, y, oldX, oldY) -> {
              form.scrollY = y;
              if (y > oldY
                  && !form.loading
                  && !form.done
                  && form.error == null
                  && ui.scroll.getChildAt(0).getHeight() - ui.scroll.getHeight() - y <= ui.dp(48))
                actions.moreFoodHistory();
            });
    update();
    ui.scrollTo(form.scrollY);
  }

  public void update() {
    while (rendered < form.hits.size()) {
      var hit = form.hits.get(rendered++);
      var card = ui.column();
      card.setBackground(ui.background(CARD, 16));
      card.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
      card.addView(
          ui.text(
              hit.date().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + " · " + hit.section(),
              15,
              INK));
      card.addView(
          ui.text(
              (hit.estimated() ? "≈" : "")
                  + Numbers.plain(hit.grams())
                  + " г · "
                  + (hit.estimated() ? "≈" : "")
                  + VaultMarkdown.fmt(hit.calories())
                  + " ккал",
              16,
              ACCENT));
      list.addView(card);
      ui.gap(list, 8);
    }
    ui.setBusy(form.loading);
    footer.setText(
        form.error != null
            ? form.error
            : form.loading
                ? "Ищем записи…"
                : form.done
                    ? form.hits.isEmpty() ? "Записей не найдено" : "Вся история загружена"
                    : "Прокрутите вниз, чтобы загрузить ещё");
    if (form.hits.stream().anyMatch(FoodHistory.Hit::estimated))
      footer.append("\n≈ — восстановлено по текущему справочнику");
    footer.setTextColor(form.error == null ? MUTED : RED);
    retry.setVisibility(form.error == null ? View.GONE : View.VISIBLE);
  }
}
