package ru.obsfit;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Android's Storage Access Framework adapter. Contains only provider and URI operations. */
public final class SafVaultStorage implements VaultStorage {
  private final Context context;
  private final Uri rootUri;
  private final Map<String, Uri> documents = new HashMap<>();

  public SafVaultStorage(Context context, Uri rootUri) {
    this.context = context.getApplicationContext();
    this.rootUri = rootUri;
  }

  @Override
  public void validateRoot() throws IOException {
    DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
    if (root == null || !root.isDirectory() || !root.canRead() || !root.canWrite())
      throw new IOException("Нет доступа на чтение и запись. Выберите папку хранилища заново.");
  }

  @Override
  public void requireDirectory(String path) throws IOException {
    Uri uri = resolve(path);
    DocumentFile file = DocumentFile.fromSingleUri(context, uri);
    if (file == null || !file.isDirectory())
      throw new IOException("Не найдена папка «" + path + "»");
  }

  private Uri resolve(String path) throws IOException {
    Uri cached = documents.get(path);
    if (cached != null) return cached;
    DocumentFile file = DocumentFile.fromTreeUri(context, rootUri);
    for (String part : path.split("/")) {
      if (file == null) break;
      file = file.findFile(part);
    }
    if (file == null)
      throw new IOException(
          "В выбранной папке не найден «" + path + "». Выберите корень YaDiskVera.");
    documents.put(path, file.getUri());
    return file.getUri();
  }

  private String readUri(Uri uri) throws IOException {
    try (InputStream in = context.getContentResolver().openInputStream(uri)) {
      if (in == null) throw new IOException("Не удалось открыть файл");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) != -1) {
        if (out.size() + n > 16 * 1024 * 1024) throw new IOException("Файл слишком большой");
        out.write(buf, 0, n);
      }
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }

  @Override
  public String read(String path) throws IOException {
    try {
      return readUri(resolve(path));
    } catch (IOException error) {
      documents.remove(path);
      return readUri(resolve(path));
    }
  }

  @Override
  public void write(String path, String text) throws IOException {
    try (OutputStream out = context.getContentResolver().openOutputStream(resolve(path), "rwt")) {
      if (out == null) throw new IOException("Файл недоступен для записи");
      out.write(text.getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
  }

  @Override
  public List<String> files(String directory) throws IOException {
    List<String> paths = new ArrayList<>();
    collect(resolve(directory), directory, paths, 0);
    return paths;
  }

  private void collect(Uri parent, String path, List<String> files, int depth) throws IOException {
    if (depth > 15) return;
    Uri children =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            rootUri, DocumentsContract.getDocumentId(parent));
    String[] columns = {
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE
    };
    Map<Uri, String> directories = new LinkedHashMap<>();
    try (Cursor cursor = context.getContentResolver().query(children, columns, null, null, null)) {
      if (cursor == null) throw new IOException("Не удалось прочитать список заметок");
      while (cursor.moveToNext()) {
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(rootUri, cursor.getString(0));
        String child = path + "/" + cursor.getString(1);
        documents.put(child, uri);
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2)))
          directories.put(uri, child);
        else files.add(child);
      }
    }
    for (var child : directories.entrySet())
      collect(child.getKey(), child.getValue(), files, depth + 1);
  }

  public void exportBackups(File source, Uri tree) throws IOException {
    DocumentFile directory = DocumentFile.fromTreeUri(context, tree);
    if (directory == null) throw new IOException("Папка экспорта недоступна");
    File[] files = source.listFiles(File::isFile);
    if (files == null || files.length == 0) throw new IOException("Резервных копий пока нет");
    for (File file : files) {
      DocumentFile target = directory.createFile("text/markdown", file.getName());
      if (target == null) throw new IOException("Не удалось создать копию в папке экспорта");
      try (InputStream in = new FileInputStream(file);
          OutputStream out = context.getContentResolver().openOutputStream(target.getUri(), "wt")) {
        if (out == null) throw new IOException("Экспорт недоступен");
        in.transferTo(out);
      }
    }
  }
}
