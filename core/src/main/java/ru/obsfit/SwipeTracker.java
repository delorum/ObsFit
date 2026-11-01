package ru.obsfit;

/**
 * Gesture intent, independent of Android touch events. Vertical scrolling must never delete an
 * item.
 */
public final class SwipeTracker {
  public enum Result {
    NONE,
    TAP,
    LEFT,
    RIGHT
  }

  private final float slop, threshold;
  private float x, y;
  private boolean horizontal, vertical;

  public SwipeTracker(float slop, float threshold) {
    this.slop = slop;
    this.threshold = threshold;
  }

  public void down(float x, float y) {
    this.x = x;
    this.y = y;
    horizontal = false;
    vertical = false;
  }

  public boolean move(float x, float y) {
    float dx = Math.abs(x - this.x), dy = Math.abs(y - this.y);
    if (!horizontal && !vertical) {
      if (dy > slop * 2 && dy > dx) vertical = true;
      else if (dx > slop * 2 && dx > dy * 1.5f) horizontal = true;
    }
    return horizontal;
  }

  public float distance(float x) {
    return x - this.x;
  }

  public Result up(float x, float y) {
    if (horizontal && Math.abs(x - this.x) > threshold)
      return x > this.x ? Result.RIGHT : Result.LEFT;
    if (!horizontal && !vertical && Math.abs(x - this.x) < slop && Math.abs(y - this.y) < slop)
      return Result.TAP;
    return Result.NONE;
  }
}
