package ru.obsfit.ui;

import android.view.*;
import ru.obsfit.SwipeTracker;

/**
 * One Android adapter for gestures in both lists. Confirmation and move actions are supplied by the
 * caller.
 */
public final class Swipes {
  private Swipes() {}

  public static void bind(Ui ui, Ui.ClickRow view, Runnable left, Runnable right) {
    SwipeTracker tracker =
        new SwipeTracker(ViewConfiguration.get(ui.activity).getScaledTouchSlop(), ui.dp(40));
    view.setOnTouchListener(
        (v, event) -> {
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
              tracker.down(event.getX(), event.getY());
              return true;
            }
            case MotionEvent.ACTION_MOVE -> {
              if (tracker.move(event.getX(), event.getY())) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                v.setTranslationX(
                    Math.max(-ui.dp(60), Math.min(ui.dp(60), tracker.distance(event.getX()))));
              }
              return true;
            }
            case MotionEvent.ACTION_UP -> {
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
              v.setTranslationX(0);
              v.getParent().requestDisallowInterceptTouchEvent(false);
              return true;
            }
          }
          return true;
        });
  }
}
