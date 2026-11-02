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
  private final Map<String, Uri> documents = new java.util.concurrent.ConcurrentHashMap<>();

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

  @Override
  public Stamp stamp(String path) throws IOException {
    try {
      return queryStamp(resolve(path));
    } catch (IOException error) {
      documents.remove(path);
      return queryStamp(resolve(path));
    }
  }

  private Stamp queryStamp(Uri uri) throws IOException {
    String[] columns = {
      DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE
    };
    try (Cursor cursor = context.getContentResolver().query(uri, columns, null, null, null)) {
      if (cursor == null || !cursor.moveToFirst()) throw new IOException("Файл недоступен");
      return new Stamp(
          cursor.isNull(0) ? null : cursor.getLong(0), cursor.isNull(1) ? null : cursor.getLong(1));
    }
  }

  /** Uses already resolved document URIs; never performs provider I/O on the UI thread. */
  public Set<Uri> observationUris(VaultRepository.Note note) {
    Set<Uri> uris = new LinkedHashSet<>();
    uris.add(rootUri);
    Uri rootDocument =
        DocumentsContract.buildDocumentUriUsingTree(
            rootUri, DocumentsContract.getTreeDocumentId(rootUri));
    uris.add(rootDocument);
    for (String path : VaultRepository.watchedPaths(note)) {
      Uri document = documents.get(path);
      if (document != null) uris.add(document);
      int slash = path.lastIndexOf('/');
      Uri parent = slash < 0 ? rootDocument : documents.get(path.substring(0, slash));
      if (parent != null)
        uris.add(
            DocumentsContract.buildChildDocumentsUriUsingTree(
                rootUri, DocumentsContract.getDocumentId(parent)));
    }
    return uris;
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
  public List<Entry> children(String directory) throws IOException {
    return listChildren(resolve(directory), directory);
  }

  private List<Entry> listChildren(Uri parent, String path) throws IOException {
    Uri children =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            rootUri, DocumentsContract.getDocumentId(parent));
    String[] columns = {
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE
    };
    List<Entry> entries = new ArrayList<>();
    try (Cursor cursor = context.getContentResolver().query(children, columns, null, null, null)) {
      if (cursor == null) throw new IOException("Не удалось прочитать список заметок");
      while (cursor.moveToNext()) {
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(rootUri, cursor.getString(0));
        String child = path + "/" + cursor.getString(1);
        documents.put(child, uri);
        entries.add(
            new Entry(child, DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))));
      }
    }
    return entries;
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
