package ru.obsfit.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.*;
import java.util.List;
import java.util.function.Consumer;

/** Shared presentation primitives. No application data or persistence lives here. */
public final class Ui {
  public static final int BG = 0xff1e232a,
      CARD = 0xff282e38,
      INK = 0xffe6e7ec,
      MUTED = 0xffa0abbd,
      ACCENT = 0xffb5a3e8,
      GREEN = 0xffa7cd65,
      YELLOW = 0xffe8be63,
      RED = 0xffef737c;

  public record Action(String icon, String description, Runnable click, Runnable longClick) {
    public Action(String icon, String description, Runnable click) {
      this(icon, description, click, null);
    }
  }

  public final AppCompatActivity activity;
  public LinearLayout body;
  public ScrollView scroll;
  private ProgressBar progress;
  private TextView error;
  private Runnable clockUpdate;

  public void updateClock() {
    if (clockUpdate != null) clockUpdate.run();
  }

  public void clockHint(LinearLayout parent, java.util.function.Supplier<String> message) {
    TextView hint = text("", 14, MUTED);
    hint.setPadding(0, dp(12), 0, dp(5));
    parent.addView(hint);
    clockUpdate =
        () -> {
          String value = message.get();
          if (!value.contentEquals(hint.getText())) hint.setText(value);
          hint.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        };
    updateClock();
  }

  public Ui(AppCompatActivity activity) {
    this.activity = activity;
  }

  public int dp(float value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  public LinearLayout column() {
    LinearLayout l = new LinearLayout(activity);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  public LinearLayout row() {
    LinearLayout l = new LinearLayout(activity);
    configureRow(l);
    return l;
  }

  /** One spacing rule for all horizontal content, including weighted buttons and clickable rows. */
  private void configureRow(LinearLayout row) {
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setBaselineAligned(false);
    GradientDrawable spacer = new GradientDrawable();
    spacer.setColor(android.graphics.Color.TRANSPARENT);
    spacer.setSize(dp(8), 0);
    row.setDividerDrawable(spacer);
    row.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
  }

  public static final class ClickRow extends LinearLayout {
    public ClickRow(Context c) {
      super(c);
      setOrientation(HORIZONTAL);
      setGravity(Gravity.CENTER_VERTICAL);
    }

    @Override
    public boolean performClick() {
      return super.performClick();
    }
  }

  public ClickRow clickRow() {
    ClickRow row = new ClickRow(activity);
    configureRow(row);
    return row;
  }

  public GradientDrawable background(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  public TextView text(String value, int size, int color) {
    TextView t = new TextView(activity);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setPadding(0, dp(5), 0, dp(5));
    return t;
  }

  public TextView bold(String value, int size, int color) {
    TextView t = text(value, size, color);
    t.setTypeface(null, Typeface.BOLD);
    return t;
  }

  public MaterialButton button(String label, Runnable click) {
    MaterialButton b = new MaterialButton(activity);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setTextColor(INK);
    b.setBackgroundTintList(ColorStateList.valueOf(0xff343c4b));
    b.setCornerRadius(dp(14));
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setOnClickListener(v -> click.run());
    return b;
  }

  public MaterialButton icon(String icon, String description, Runnable click) {
    MaterialButton b = button(icon, click);
    b.setTextSize(
        switch (icon) {
          case "≡", "↻", "⚙" -> 28;
          case "‹", "›" -> 34;
          case "ккал" -> 14;
          default -> 22;
        });
    b.setIncludeFontPadding(false);
    b.setPadding(0, 0, 0, 0);
    if (icon.equals("↻")) {
      b.setText("");
      b.setIcon(
          androidx.appcompat.content.res.AppCompatResources.getDrawable(
              activity, ru.obsfit.R.drawable.ic_refresh));
      b.setIconTint(ColorStateList.valueOf(INK));
      b.setIconSize(dp(32));
      b.setIconPadding(0);
      b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
      b.setGravity(Gravity.CENTER);
    }
    b.setContentDescription(description);
    ViewCompat.setTooltipText(b, description);
    return b;
  }

  /** Layout dimensions in dp. Spacing belongs to the containing row. */
  public LinearLayout.LayoutParams actionLayout(int width, int height) {
    return new LinearLayout.LayoutParams(dp(width), dp(height));
  }

  public void gap(LinearLayout parent, int height) {
    parent.addView(new Space(activity), new LinearLayout.LayoutParams(1, dp(height)));
  }

  public void weighted(LinearLayout parent, View view) {
    parent.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
  }

  public void page(
      String title, String subtitle, Runnable back, List<Action> actions, boolean busy) {
    clockUpdate = null;
    LinearLayout root = column();
    root.setBackgroundColor(BG);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          var bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
          v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    LinearLayout bar = row();
    bar.setPadding(dp(16), dp(4), dp(16), dp(4));
    if (back != null) {
      var backParams = new LinearLayout.LayoutParams(dp(48), dp(52));
      bar.addView(icon("‹", "Назад", back), backParams);
    }
    LinearLayout titles = column();
    titles.addView(bold(title, 23, INK));
    if (subtitle != null) titles.addView(text(subtitle, 12, MUTED));
    weighted(bar, titles);
    for (Action action : actions) {
      MaterialButton b = icon(action.icon(), action.description(), action.click());
      if (action.longClick() != null)
        b.setOnLongClickListener(
            v -> {
              action.longClick().run();
              return true;
            });
      bar.addView(b, actionLayout(48, 52));
    }
    root.addView(bar);
    progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
    progress.setIndeterminate(true);
    progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
    root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
    setBusy(busy);
    error = text("", 14, RED);
    error.setPadding(dp(20), dp(8), dp(20), dp(8));
    error.setVisibility(View.GONE);
    root.addView(error);
    scroll = new ScrollView(activity);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    body = column();
    body.setPadding(dp(20), dp(12), dp(20), dp(32));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    activity.setContentView(root);
    ViewCompat.requestApplyInsets(root);
  }

  public EditText field(
      LinearLayout parent, String label, String value, boolean decimal, boolean enabled) {
    TextInputLayout box = new TextInputLayout(activity);
    box.setHint(label);
    box.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
    box.setBoxCornerRadii(dp(14), dp(14), dp(14), dp(14));
    box.setDefaultHintTextColor(ColorStateList.valueOf(MUTED));
    TextInputEditText edit = new TextInputEditText(box.getContext());
    edit.setTextColor(INK);
    edit.setTextSize(17);
    edit.setSingleLine(true);
    edit.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    edit.setIncludeFontPadding(false);
    edit.setPaddingRelative(dp(16), dp(16), dp(16), dp(16));
    edit.setMinHeight(dp(56));
    edit.setInputType(
        decimal
            ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    edit.setText(value);
    edit.setEnabled(enabled);
    box.addView(edit, new LinearLayout.LayoutParams(-1, -2));
    parent.addView(box, new LinearLayout.LayoutParams(-1, -2));
    gap(parent, 12);
    return edit;
  }

  public void multiline(EditText field, int lines) {
    field.setSingleLine(false);
    field.setMinLines(lines);
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
  }

  public void watch(EditText field, Consumer<String> callback) {
    field.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

          public void onTextChanged(CharSequence s, int a, int b, int c) {
            callback.accept(s.toString());
          }

          public void afterTextChanged(Editable e) {}
        });
  }

  public void setBusy(boolean busy) {
    if (progress != null) progress.setVisibility(busy ? View.VISIBLE : View.GONE);
  }

  public void error(String message) {
    if (error != null) {
      error.setText(message);
      error.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }
  }

  public void scrollTo(int y) {
    scroll.post(() -> scroll.scrollTo(0, y));
  }
}
