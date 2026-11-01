package ru.obsfit;

import static org.junit.Assert.*;

import org.junit.Test;

public final class SwipeTest {
  @Test
  public void horizontalGesturesHaveOppositeActions() {
    SwipeTracker s = new SwipeTracker(8, 40);
    s.down(100, 100);
    assertTrue(s.move(150, 102));
    assertEquals(SwipeTracker.Result.RIGHT, s.up(160, 102));
    s.down(100, 100);
    assertTrue(s.move(50, 100));
    assertEquals(SwipeTracker.Result.LEFT, s.up(40, 100));
  }

  @Test
  public void verticalScrollCanNeverBecomeDelete() {
    SwipeTracker s = new SwipeTracker(8, 40);
    s.down(100, 100);
    assertFalse(s.move(102, 140));
    assertFalse(s.move(20, 180));
    assertEquals(SwipeTracker.Result.NONE, s.up(20, 180));
  }

  @Test
  public void shortDragDoesNotOpenDialogOrEdit() {
    SwipeTracker s = new SwipeTracker(8, 40);
    s.down(100, 100);
    assertTrue(s.move(120, 100));
    assertEquals(SwipeTracker.Result.NONE, s.up(125, 100));
  }

  @Test
  public void tapStillOpensEditor() {
    SwipeTracker s = new SwipeTracker(8, 40);
    s.down(100, 100);
    assertEquals(SwipeTracker.Result.TAP, s.up(103, 104));
  }

  @Test
  public void diagonalMovementIsNotAHorizontalSwipe() {
    SwipeTracker s = new SwipeTracker(8, 40);
    s.down(100, 100);
    assertFalse(s.move(140, 140));
    assertEquals(SwipeTracker.Result.NONE, s.up(140, 140));
  }

  @Test
  public void dragReturningToStartIsNotATap() {
    SwipeTracker s = new SwipeTracker(8, 40);
    s.down(100, 100);
    s.move(130, 100);
    assertEquals(SwipeTracker.Result.NONE, s.up(102, 101));
  }

  @Test
  public void nextGestureResetsScrollIntent() {
    SwipeTracker s = new SwipeTracker(8, 40);
    s.down(100, 100);
    s.move(100, 150);
    s.down(100, 100);
    assertTrue(s.move(10, 100));
    assertEquals(SwipeTracker.Result.LEFT, s.up(10, 100));
  }
}
