package ru.obsfit;

/** Hold eligibility; Android schedules fire after its standard long-press timeout. */
public final class HoldTracker {
  private final float slop;
  private float x, y;
  private boolean armed, held;

  public HoldTracker(float slop) {
    this.slop = slop;
  }

  public void down(float x, float y) {
    this.x = x;
    this.y = y;
    armed = true;
    held = false;
  }

  public void move(float x, float y) {
    if (Math.hypot(x - this.x, y - this.y) > slop) armed = false;
  }

  public void cancel() {
    armed = false;
  }

  public boolean fire() {
    if (!armed) return false;
    armed = false;
    held = true;
    return true;
  }

  public boolean held() {
    return held;
  }
}
