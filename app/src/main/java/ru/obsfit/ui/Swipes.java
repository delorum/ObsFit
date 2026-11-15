package ru.obsfit.ui;

import android.view.*;
import ru.obsfit.SwipeTracker;

/**
 * One Android adapter for gestures in all food lists. Gesture actions are supplied by the caller.
 */
public final class Swipes {
  private Swipes() {}

  public static void bind(Ui ui, Ui.ClickRow view, Runnable left, Runnable right) {
    bind(ui, view, left, right, null);
  }

  public static void bind(Ui ui, Ui.ClickRow view, Runnable left, Runnable right, Runnable hold) {
    int slop = ViewConfiguration.get(ui.activity).getScaledTouchSlop();
    ru.obsfit.HoldTracker holding = new ru.obsfit.HoldTracker(slop);
    Runnable longPress =
        () -> {
          if (hold != null && view.isAttachedToWindow() && holding.fire()) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            hold.run();
          }
        };
    if (hold != null)
      view.setOnLongClickListener(
          v -> {
            hold.run();
            return true;
          });
    SwipeTracker tracker =
        new SwipeTracker(ViewConfiguration.get(ui.activity).getScaledTouchSlop(), ui.dp(40));
    view.setOnTouchListener(
        (v, event) -> {
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
              view.removeCallbacks(longPress);
              holding.down(event.getX(), event.getY());
              tracker.down(event.getX(), event.getY());
              if (hold != null)
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
              return true;
            }
            case MotionEvent.ACTION_MOVE -> {
              holding.move(event.getX(), event.getY());
              if (holding.held()) return true;
              if (tracker.move(event.getX(), event.getY())) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                v.setTranslationX(
                    Math.max(-ui.dp(60), Math.min(ui.dp(60), tracker.distance(event.getX()))));
              }
              return true;
            }
            case MotionEvent.ACTION_UP -> {
              view.removeCallbacks(longPress);
              holding.cancel();
              if (holding.held()) return true;
              v.animate().translationX(0).setDuration(120).start();
              v.getParent().requestDisallowInterceptTouchEvent(false);
              switch (tracker.up(event.getX(), event.getY())) {
                case LEFT -> left.run();
                case RIGHT -> right.run();
                case TAP -> v.performClick();
                case NONE -> {}
              }
              return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
              holding.cancel();
              view.removeCallbacks(longPress);
              v.setTranslationX(0);
              v.getParent().requestDisallowInterceptTouchEvent(false);
              return true;
            }
          }
          return true;
        });
  }
}
