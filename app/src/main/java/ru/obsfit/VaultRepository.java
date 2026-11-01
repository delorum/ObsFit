package ru.obsfit;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** SAF adapter. Every write is guarded against a stale snapshot and backed up privately. */
public final class VaultRepository {
    private final Context context;
    private final Uri rootUri;
    public static final class Note {
        public final LocalDate date;
        public final Uri uri;
        public final String name;
        Note(LocalDate date,Uri uri,String name) { this.date=date;this.uri=uri;this.name=name; }
    }
    public static final class Snapshot {
        public final Note note;
        public final String foodText, replacementsText;
        public final VaultMarkdown.Catalog catalog;
        public final VaultMarkdown.Day day;
        Snapshot(Note note,String foodText,String replacementsText,VaultMarkdown.Catalog catalog,VaultMarkdown.Day day) {
            this.note=note;this.foodText=foodText;this.replacementsText=replacementsText;this.catalog=catalog;this.day=day;
        }
    }
    public VaultRepository(Context context,Uri rootUri) { this.context=context.getApplicationContext();this.rootUri=rootUri; }
    public void validateRoot() throws IOException {
        DocumentFile root=DocumentFile.fromTreeUri(context,rootUri);
        if(root==null||!root.isDirectory()||!root.canRead()||!root.canWrite()) throw new IOException("Нет доступа на чтение и запись. Выберите папку хранилища заново.");
        VaultMarkdown.catalog(read(resolve("Еда.md")));
        resolve("Дневник/Хроника"); resolve("Замены.md");
    }
    private Uri resolve(String path) throws IOException {
        DocumentFile file=DocumentFile.fromTreeUri(context,rootUri);
        for(String part:path.split("/")) { if(file==null) break;file=file.findFile(part); }
        if(file==null) throw new IOException("В выбранной папке не найден «"+path+"». Выберите корень YaDiskVera.");
        return file.getUri();
    }
    public String read(Uri uri) throws IOException {
        try(InputStream in=context.getContentResolver().openInputStream(uri)) {
            if(in==null) throw new IOException("Не удалось открыть файл");
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[16384];int n;
            while((n=in.read(buf))!=-1) { if(out.size()+n>16*1024*1024) throw new IOException("Файл слишком большой");out.write(buf,0,n); }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    public List<Note> notes() throws IOException {
        List<Note> result=new ArrayList<>();collect(resolve("Дневник/Хроника"),result,0);
        result.sort(Comparator.comparing(note->note.date));
        for(int i=1;i<result.size();i++) if(result.get(i-1).date.equals(result.get(i).date)) throw new IOException("Найдены две ежедневные заметки на "+result.get(i).date+". Устраните дубликат или конфликт Syncthing.");
        return result;
    }
    private void collect(Uri parent,List<Note> notes,int depth) throws IOException {
        if(depth>15) return;
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(rootUri,DocumentsContract.getDocumentId(parent));
        String[] fields={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE};
        List<Uri> directories=new ArrayList<>();
        try(Cursor c=context.getContentResolver().query(children,fields,null,null,null)) {
            if(c==null) throw new IOException("Не удалось прочитать список заметок");
            while(c.moveToNext()) {
                String id=c.getString(0), name=c.getString(1), mime=c.getString(2);
                Uri uri=DocumentsContract.buildDocumentUriUsingTree(rootUri,id);
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) directories.add(uri);
                else { LocalDate date=VaultMarkdown.date(name);if(date!=null) notes.add(new Note(date,uri,name)); }
            }
        }
        for(Uri directory:directories) collect(directory,notes,depth+1);
    }
    public Snapshot load(Note note) throws IOException {
        String food=read(resolve("Еда.md")), replacements=read(resolve("Замены.md"));
        VaultMarkdown.Catalog catalog=VaultMarkdown.catalog(food);
        return new Snapshot(note,food,replacements,catalog,VaultMarkdown.day(read(note.uri),catalog));
    }
    public static final class CatalogSnapshot {
        public final String foodText,replacementsText;
        public final VaultMarkdown.Catalog catalog;
        CatalogSnapshot(String food,String replacements) {foodText=food;replacementsText=replacements;catalog=VaultMarkdown.catalog(food);}
    }
    public CatalogSnapshot loadCatalog() throws IOException {
        return new CatalogSnapshot(read(resolve("Еда.md")),read(resolve("Замены.md")));
    }
    public synchronized void saveCatalog(CatalogSnapshot original,String updated) throws IOException {
        VaultMarkdown.catalog(updated);
        if(!read(resolve("Замены.md")).equals(original.replacementsText))throw new IOException("Замены.md изменился. Обновите справочник и повторите действие.");
        write(resolve("Еда.md"),original.foodText,updated,"Еда.md");
    }
    /** A provider cannot transact across files. Catalog may be saved even if the day changed concurrently. */
    public synchronized void save(Snapshot original,String noteText,VaultMarkdown.Food newFood) throws IOException {
        Uri foodUri=resolve("Еда.md");
        if(!read(original.note.uri).equals(original.day.source)||!read(foodUri).equals(original.foodText)||!read(resolve("Замены.md")).equals(original.replacementsText)) throw stale();
        if(newFood!=null) {
            String catalogText=VaultMarkdown.addFood(original.catalog,newFood,original.replacementsText);
            write(foodUri,original.foodText,catalogText,"Еда.md");
            try { write(original.note.uri,original.day.source,noteText,original.note.name); }
            catch(IOException error) { throw new IOException("Еда сохранена в справочник, но дневная запись не добавлена: "+error.getMessage()+" Обновите день и выберите её из списка.",error); }
        } else write(original.note.uri,original.day.source,noteText,original.note.name);
    }
    private IOException stale() { return new IOException("Файл изменился после загрузки, возможно при синхронизации. Обновите данные (↻) и повторите действие."); }
    private void write(Uri uri,String expected,String updated,String name) throws IOException {
        if(expected.equals(updated)) return;
        if(!read(uri).equals(expected)) throw stale();
        File backups=new File(context.getFilesDir(),"backups");
        if(!backups.isDirectory()&&!backups.mkdirs()) throw new IOException("Не удалось создать резервную копию");
        String safeName=name.replaceAll("[^\\p{L}\\p{N}_.-]","_");
        File backup=new File(backups,System.currentTimeMillis()+"-"+UUID.randomUUID()+"-"+safeName);
        try(FileOutputStream out=new FileOutputStream(backup)) { out.write(expected.getBytes(StandardCharsets.UTF_8));out.getFD().sync(); }
        if(!read(uri).equals(expected)) throw stale();
        try(OutputStream out=context.getContentResolver().openOutputStream(uri,"rwt")) {
            if(out==null) throw new IOException("Файл недоступен для записи");out.write(updated.getBytes(StandardCharsets.UTF_8));out.flush();
        } catch(Exception e) { throw new IOException("Не удалось завершить запись. Резервная копия: "+backup.getName()+". "+e.getMessage(),e); }
        if(!read(uri).equals(updated)) throw new IOException("После записи содержимое изменилось. Проверьте синхронизацию; резервная копия: "+backup.getName());
        File[] files=backups.listFiles(); if(files!=null&&files.length>100) { Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(int i=0;i<files.length-100;i++) files[i].delete(); }
    }
    public void exportBackups(Uri tree) throws IOException {
        DocumentFile directory=DocumentFile.fromTreeUri(context,tree);
        if(directory==null) throw new IOException("Папка экспорта недоступна");
        File[] files=new File(context.getFilesDir(),"backups").listFiles();
        if(files==null||files.length==0) throw new IOException("Резервных копий пока нет");
        for(File file:files) {
            DocumentFile target=directory.createFile("text/markdown",file.getName());
            if(target==null) throw new IOException("Не удалось создать копию в папке экспорта");
            try(InputStream in=new FileInputStream(file);OutputStream out=context.getContentResolver().openOutputStream(target.getUri(),"wt")) { if(out==null) throw new IOException("Экспорт недоступен");in.transferTo(out); }
        }
    }
}
