package ru.obsfit.ui;

import android.content.ClipData;
import android.view.*;
import ru.obsfit.*;

/** Native drag after a hold, independent of immediate taps and horizontal swipes. */
final class DiaryDrag {
  private DiaryDrag() {}

  static void start(View row, RecipeBlocks.Piece piece) {
    row.startDragAndDrop(
        ClipData.newPlainText("ObsFit", ""), new View.DragShadowBuilder(row), piece, 0);
  }

  static void target(
      Ui ui,
      View view,
      String section,
      RecipeBlocks.Group group,
      RecipeBlocks.Piece after,
      AppState state,
      DayScreen.Actions actions) {
    view.setOnDragListener(
        (v, event) -> {
          if (!(event.getLocalState() instanceof RecipeBlocks.Piece piece)
              || !piece.section().equals(section)) return false;
          if (group != null && group.line() >= piece.line() && group.line() < piece.end())
            return false;
          switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED -> {
              return !state.busy;
            }
            case DragEvent.ACTION_DRAG_ENTERED -> v.setBackground(ui.background(0xff344757, 10));
            case DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> v.setBackground(null);
            case DragEvent.ACTION_DRAG_LOCATION -> {
              int[] location = new int[2], scroll = new int[2];
              v.getLocationOnScreen(location);
              ui.scroll.getLocationOnScreen(scroll);
              float y = location[1] + event.getY() - scroll[1];
              if (y < ui.dp(60)) ui.scroll.scrollBy(0, -ui.dp(12));
              else if (y > ui.scroll.getHeight() - ui.dp(60)) ui.scroll.scrollBy(0, ui.dp(12));
            }
            case DragEvent.ACTION_DROP -> {
              v.setBackground(null);
              if (!state.busy) actions.reparent(piece, group, after);
              return true;
            }
            default -> {}
          }
          return true;
        });
  }
}
