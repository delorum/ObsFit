package ru.obsfit;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import java.util.Set;

/** Best-effort provider notifications; metadata polling remains the fallback. */
final class VaultObserver implements AutoCloseable {
  private final ContentResolver resolver;
  private final ContentObserver observer;
  private Set<Uri> watched = Set.of();

  VaultObserver(ContentResolver resolver, Handler handler, Runnable changed) {
    this.resolver = resolver;
    observer =
        new ContentObserver(handler) {
          @Override
          public void onChange(boolean selfChange) {
            changed.run();
          }

          @Override
          public void onChange(boolean selfChange, Uri uri) {
            changed.run();
          }
        };
  }

  void watch(Set<Uri> uris) {
    if (watched.equals(uris)) return;
    close();
    for (Uri uri : uris) {
      try {
        resolver.registerContentObserver(uri, true, observer);
      } catch (SecurityException | IllegalArgumentException error) {
        Log.d("ObsFitRefresh", "Provider notifications unavailable", error);
      }
    }
    watched = Set.copyOf(uris);
  }

  @Override
  public void close() {
    resolver.unregisterContentObserver(observer);
    watched = Set.of();
  }
}
