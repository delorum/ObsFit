package ru.obsfit;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.text.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.TextInputEditText;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class MainActivity extends AppCompatActivity {
    private static final int BG=0xff1e232a, CARD=0xff282e38, INK=0xffe6e7ec, MUTED=0xffa0abbd, ACCENT=0xffb5a3e8;
    private static final int GREEN=0xffa7cd65, YELLOW=0xffe8be63, RED=0xffef737c;
    private static final int PICK_VAULT=101, SCAN=102, EXPORT=103, SCAN_CATALOG=104;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private VaultRepository repo;
    private List<VaultRepository.Note> notes=new ArrayList<>();
    private VaultRepository.Snapshot snapshot;
    private LocalDate selected=LocalDate.now();
    private final Set<String> expanded=new HashSet<>(VaultMarkdown.SECTIONS);
    private LinearLayout root, content;
    private ScrollView scroll;
    private ProgressBar busyBar;
    private TextView errorView;
    private String screen="settings";
    private boolean busy=false, resumed=false;
    private int scrollY=0;
    private Form form;
    private Bundle pendingDraft,pendingCatalogDraft,catalogReturnForm;
    private VaultRepository.CatalogSnapshot catalogSnapshot;
    private CatalogEdit catalogEdit;
    private String catalogQuery="";
    private String pendingCatalogBarcode;
    private final Runnable poll=new Runnable() { @Override public void run() {
        if(resumed&&!busy&&screen.equals("day")&&snapshot!=null) reload(false);
        if(resumed) handler.postDelayed(this,12000);
    }};

    @Override public void onCreate(Bundle state) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(state);
        getOnBackPressedDispatcher().addCallback(this,new androidx.activity.OnBackPressedCallback(true) { @Override public void handleOnBackPressed() { back(); } });
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        prefs=getSharedPreferences("obsfit",MODE_PRIVATE);
        if(state!=null) { try { selected=LocalDate.parse(state.getString("date",LocalDate.now().toString())); } catch(Exception ignored) {} }
        if(state!=null) {pendingDraft=state.getBundle("form");pendingCatalogDraft=state.getBundle("catalogDraft");catalogQuery=state.getString("catalogQuery","");pendingCatalogBarcode=state.getString("catalogBarcode");catalogReturnForm=state.getBundle("catalogReturnForm");}
        showSettings();
        String saved=prefs.getString("vault",null);
        if(saved!=null) connect(Uri.parse(saved),false);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);state.putString("date",selected.toString());
        if(form!=null && form.name!=null && form.fields.getVisibility()==View.VISIBLE)state.putBundle("form",captureFormDraft(form));
        state.putBundle("catalogReturnForm",catalogReturnForm);
        state.putString("catalogQuery",catalogQuery);
        state.putString("catalogBarcode",pendingCatalogBarcode);
        if(catalogEdit!=null) {
            CatalogEdit f=catalogEdit;Bundle draft=new Bundle();draft.putInt("hash",f.original.foodText.hashCode());draft.putString("oldName",f.food==null?null:f.food.name);
            draft.putString("name",f.name.getText().toString());draft.putString("aliases",f.aliases.getText().toString());draft.putString("weight",f.weight.getText().toString());draft.putString("kcal100",f.kcal100.getText().toString());draft.putString("ingredients",RecipeIngredients.toMarkdown(f.ingredients));draft.putBoolean("ingredientsExpanded",f.ingredientsExpanded);state.putBundle("catalogDraft",draft);
        }
    }
    private Bundle captureFormDraft(Form f) {
        Bundle draft=new Bundle();
        draft.putString("section",f.section);draft.putBoolean("new",f.newFood);draft.putBoolean("manual",f.manual);draft.putBoolean("dirty",f.dirty);
        draft.putString("selectedFood",f.selectedFood==null?null:f.selectedFood.name);
        draft.putString("entryRaw",f.entry==null?null:f.entry.raw);draft.putInt("entryLine",f.entry==null?-1:f.entry.line);
        draft.putString("name",f.name.getText().toString());draft.putString("aliases",f.aliases.getText().toString());
        if(f.manual)draft.putString("manualKcal",f.manualKcal.getText().toString());
        else {draft.putString("weight",f.weight.getText().toString());draft.putString("kcal100",f.kcal100.getText().toString());}
        if(f.newFood)draft.putString("defaultWeight",f.defaultWeight.getText().toString());
        return draft;
    }
    @Override protected void onResume() { super.onResume();resumed=true;handler.removeCallbacks(poll);handler.postDelayed(poll,12000); if(repo!=null&&snapshot!=null&&screen.equals("day")&&!busy) reload(false); }
    @Override protected void onPause() { resumed=false;handler.removeCallbacks(poll);super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacks(poll);io.shutdown();super.onDestroy(); }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color,int radius) { GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d; }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l; }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l; }
    private TextView text(String value,int size,int color) { TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));return t; }
    private TextView bold(String value,int size,int color) { TextView t=text(value,size,color);t.setTypeface(null,Typeface.BOLD);return t; }
    private MaterialButton button(String label,Runnable click) {
        MaterialButton b=new MaterialButton(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(INK);b.setBackgroundTintList(ColorStateList.valueOf(0xff343c4b));b.setCornerRadius(dp(14));b.setMinWidth(0);b.setMinimumWidth(0);b.setOnClickListener(v->click.run());return b;
    }
    private void gap(LinearLayout parent,int height) { Space s=new Space(this);parent.addView(s,new LinearLayout.LayoutParams(1,dp(height))); }
    private void weighted(LinearLayout parent,View v) { parent.addView(v,new LinearLayout.LayoutParams(0,-2,1)); }
    private void page(String title,String subtitle,boolean back) {
        root=column();root.setBackgroundColor(BG);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        LinearLayout bar=row();bar.setPadding(dp(16),dp(4),dp(16),dp(4));
        if(back) { MaterialButton b=button("‹",this::back);b.setContentDescription("Назад");bar.addView(b,new LinearLayout.LayoutParams(dp(48),dp(52))); }
        LinearLayout titles=column();titles.addView(bold(title,23,INK));if(subtitle!=null) titles.addView(text(subtitle,12,MUTED));weighted(bar,titles);
        if(!back&&screen.equals("day")) {
            MaterialButton catalog=button("≡",this::openCatalog);catalog.setTextSize(22);catalog.setContentDescription("Справочник еды");ViewCompat.setTooltipText(catalog,"Справочник еды");bar.addView(catalog,new LinearLayout.LayoutParams(dp(48),dp(52)));
            MaterialButton refresh=button("↻",()->reload(true));refresh.setContentDescription("Обновить данные из хранилища");bar.addView(refresh,new LinearLayout.LayoutParams(dp(48),dp(52)));
            MaterialButton settings=button("⚙",()->{if(!busy) showSettings();});settings.setContentDescription("Настройки");bar.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(52)));
        }
        if(screen.equals("catalog")) {MaterialButton refresh=button("↻",this::openCatalog);refresh.setContentDescription("Обновить справочник");bar.addView(refresh,new LinearLayout.LayoutParams(dp(48),dp(52)));}
        if(screen.equals("catalog-edit")) {
            MaterialButton scan=button("▣",()->{if(!busy)startActivityForResult(new Intent(this,ScannerActivity.class),SCAN_CATALOG);});
            scan.setTextSize(22);scan.setPadding(0,0,0,0);scan.setContentDescription("Сканировать штрихкод. Удерживайте для ввода вручную.");ViewCompat.setTooltipText(scan,"Сканировать штрихкод");
            scan.setOnLongClickListener(v->{if(!busy)manualBarcode(code->{pendingCatalogBarcode=code;consumeCatalogBarcode();});return true;});
            bar.addView(scan,new LinearLayout.LayoutParams(dp(48),dp(52)));
        }
        root.addView(bar);
        busyBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);busyBar.setIndeterminate(true);busyBar.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));busyBar.setVisibility(busy?View.VISIBLE:View.GONE);root.addView(busyBar,new LinearLayout.LayoutParams(-1,dp(3)));
        errorView=text("",14,RED);errorView.setPadding(dp(20),dp(8),dp(20),dp(8));errorView.setVisibility(View.GONE);root.addView(errorView);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        content=column();content.setPadding(dp(20),dp(12),dp(20),dp(32));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);ViewCompat.requestApplyInsets(root);
    }
    private void error(String message) { if(errorView!=null) { errorView.setText(message);errorView.setVisibility(View.VISIBLE); } }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    private interface Work<T> { T run() throws Exception; }
    private <T> void task(Work<T> work,Consumer<T> done) {
        if(busy) return;busy=true;if(busyBar!=null)busyBar.setVisibility(View.VISIBLE);if(errorView!=null)errorView.setVisibility(View.GONE);
        io.execute(()->{
            try { T value=work.run();runOnUiThread(()->{if(isDestroyed())return;busy=false;busyBar.setVisibility(View.GONE);done.accept(value);}); }
            catch(Exception e) { runOnUiThread(()->{if(isDestroyed())return;busy=false;busyBar.setVisibility(View.GONE);error(e.getMessage()==null?e.toString():e.getMessage());}); }
        });
    }
    private void showSettings() {
        screen="settings";form=null;page("Настройки","ObsFit · локальное хранилище",repo!=null);
        content.addView(bold("Хранилище Obsidian",23,INK));gap(content,12);
        content.addView(text("Выберите корневую папку YaDiskVera. Пока доступ к ней не задан, дневник и справочник недоступны.",16,MUTED));gap(content,16);
        LinearLayout card=column();card.setPadding(dp(18),dp(12),dp(18),dp(12));card.setBackground(background(CARD,20));
        card.addView(text("ПАПКА НА ТЕЛЕФОНЕ",11,ACCENT));
        String path=prefs.getString("vault",null);
        String display="/Documents/obs/YaDiskVera";
        if(path!=null) try { display="/"+DocumentsContract.getTreeDocumentId(Uri.parse(path)).replace("primary:",""); } catch(Exception ignored) {}
        card.addView(text(display,17,INK));card.addView(text(path==null?"Доступ ещё не предоставлен":"Папка выбрана через Android",12,MUTED));content.addView(card);gap(content,14);
        content.addView(button(path==null?"Выбрать папку хранилища":"Изменить папку",()->{
            if(busy)return;
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Documents/obs/YaDiskVera"));
            startActivityForResult(intent,PICK_VAULT);
        }));
        if(repo!=null) { content.addView(button("Открыть дневник",()->{if(!busy)showDay();}));gap(content,20); }
        content.addView(bold("Дневная норма",18,INK));content.addView(text("2600 ккал · до 2500 — зелёный, 2500–2600 — жёлтый, выше — красный.",15,MUTED));gap(content,18);
        content.addView(bold("Синхронизация и копии",18,INK));content.addView(text("Изменения сохраняются прямо в Markdown. Syncthing переносит файлы между устройствами. Данные перечитываются при открытии приложения, каждые 12 секунд на экране дня и по кнопке ↻.",15,MUTED));
        content.addView(text("Перед каждой записью ObsFit сохраняет приватную резервную копию. Хранятся последние 100 копий; их можно выгрузить в выбранную папку.",15,MUTED));
        content.addView(button("Экспортировать резервные копии",()->{
            if(repo==null) { toast("Сначала выберите хранилище");return; }if(busy)return;
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),EXPORT);
        }));gap(content,18);
        content.addView(bold("Штрихкоды",18,INK));content.addView(text("Камера распознаёт код на устройстве. Для поиска товара только штрихкод отправляется в Open Food Facts. Нужен интернет; база может не содержать товар. Проверяйте данные по упаковке. Данные Open Food Facts — ODbL.",15,MUTED));
        content.addView(button("Open Food Facts ↗",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://world.openfoodfacts.org")))));
        content.addView(text("ObsFit 0.1.10 · Android-прототип",12,MUTED));
    }
    private void connect(Uri uri,boolean save) {
        VaultRepository candidate=new VaultRepository(this,uri);
        task(()->{candidate.validateRoot();return candidate.notes();},list->{
            repo=candidate;notes=list;if(save)prefs.edit().putString("vault",uri.toString()).apply();
            if(pendingCatalogDraft!=null){openCatalog();return;}
            if(!notes.isEmpty()) { if(notes.stream().noneMatch(n->n.date.equals(selected))) selected=nearestDate(notes,LocalDate.now());reload(false); }
            else showDay();
        });
    }
    private LocalDate nearestDate(List<VaultRepository.Note> list,LocalDate date) {
        LocalDate found=list.get(0).date;for(VaultRepository.Note note:list)if(!note.date.isAfter(date))found=note.date;return found;
    }
    private VaultRepository.Note currentNote() { for(VaultRepository.Note note:notes)if(note.date.equals(selected))return note;return null; }
    private void reload(boolean rescan) {
        if(repo==null||busy)return;
        scrollY=screen.equals("day")?scroll.getScrollY():0;
        task(()->{
            List<VaultRepository.Note> updated=rescan?repo.notes():notes;
            if(updated.isEmpty()) return new Loaded(updated,null);
            VaultRepository.Note chosen=updated.stream().filter(n->n.date.equals(selected)).findFirst().orElse(null);
            if(chosen==null) { LocalDate date=nearestDate(updated,selected);chosen=updated.stream().filter(n->n.date.equals(date)).findFirst().orElseThrow(); }
            return new Loaded(updated,repo.load(chosen));
        },loaded->{
            notes=loaded.notes;VaultRepository.Snapshot previous=snapshot;snapshot=loaded.snapshot;
            if(snapshot!=null)selected=snapshot.note.date;
            if(!screen.equals("day")||previous==null||snapshot==null||!previous.note.date.equals(snapshot.note.date)||!previous.day.source.equals(snapshot.day.source)||!previous.foodText.equals(snapshot.foodText)||rescan) showDay();
            if(pendingDraft!=null && snapshot!=null) restoreDraft();
        });
    }
    private void restoreDraft() {
        Bundle draft=pendingDraft;pendingDraft=null;
        VaultMarkdown.Entry entry=null;
        String raw=draft.getString("entryRaw");
        if(raw!=null) {
            for(VaultMarkdown.Entry item:snapshot.day.entries)if(item.raw.equals(raw)&&item.line==draft.getInt("entryLine")) {entry=item;break;}
            if(entry==null) {error("Редактируемая запись изменилась. Откройте её заново.");return;}
        }
        screen="form";form=new Form(snapshot,draft.getString("section","Заметки"),entry);
        form.newFood=draft.getBoolean("new");form.manual=draft.getBoolean("manual");
        String foodName=draft.getString("selectedFood");form.selectedFood=foodName==null?null:snapshot.catalog.get(foodName);
        if(foodName!=null&&form.selectedFood==null) {showDay();error("Еда исчезла из справочника. Откройте запись заново.");return;}
        renderForm();showFields();
        if(form.newFood||form.selectedFood==null) {form.name.setText(draft.getString("name",""));form.aliases.setText(draft.getString("aliases",""));}
        if(form.manual)form.manualKcal.setText(draft.getString("manualKcal",""));
        else {form.weight.setText(draft.getString("weight",""));if(form.newFood||form.selectedFood==null)form.kcal100.setText(draft.getString("kcal100",""));}
        if(form.newFood)form.defaultWeight.setText(draft.getString("defaultWeight",""));
        form.dirty=draft.getBoolean("dirty",true);
    }
    private record Loaded(List<VaultRepository.Note> notes,VaultRepository.Snapshot snapshot) {}
    private void showDay() {
        screen="day";form=null;page("ObsFit","Дневник питания",false);
        if(notes.isEmpty()) { content.addView(bold("Нет ежедневных заметок",24,INK));content.addView(text("В Дневник/Хроника пока нет заметок с датами. Создайте день в Obsidian и нажмите ↻.",16,MUTED));return; }
        if(snapshot==null) { content.addView(text("Загружаем день…",18,MUTED));reload(false);return; }
        LinearLayout dates=row();MaterialButton prev=button("‹",()->navigate(-1)),next=button("›",()->navigate(1));prev.setContentDescription("Предыдущая заметка");next.setContentDescription("Следующая заметка");
        int at=0;for(int i=0;i<notes.size();i++)if(notes.get(i).date.equals(selected))at=i;
        prev.setEnabled(at>0);next.setEnabled(at<notes.size()-1);
        prev.setAlpha(prev.isEnabled()?1f:0.28f);next.setAlpha(next.isEnabled()?1f:0.28f);
        dates.addView(prev,new LinearLayout.LayoutParams(dp(48),dp(52)));
        LinearLayout dateText=column();TextView date=bold(selected.format(DateTimeFormatter.ofPattern("d MMMM",Locale.forLanguageTag("ru"))),26,INK);date.setGravity(Gravity.CENTER);
        TextView week=text(selected.format(DateTimeFormatter.ofPattern("EEEE · yyyy",Locale.forLanguageTag("ru"))),13,MUTED);week.setGravity(Gravity.CENTER);
        dateText.addView(date);dateText.addView(week);dateText.setOnClickListener(v->chooseDate());dateText.setContentDescription("Выбрать дату");weighted(dates,dateText);dates.addView(next,new LinearLayout.LayoutParams(dp(48),dp(52)));content.addView(dates);gap(content,20);
        double total=snapshot.day.total;int color=total>2600?RED:total>=2500?YELLOW:GREEN;
        LinearLayout calories=column();calories.setBackground(background(CARD,22));calories.setPadding(dp(20),dp(16),dp(20),dp(20));
        calories.addView(text("СЪЕДЕНО ЗА ДЕНЬ",11,MUTED));
        TextView amount=bold(VaultMarkdown.fmt(total)+" / 2600 ккал",30,color);calories.addView(amount);
        LinearProgressIndicator progress=new LinearProgressIndicator(this);progress.setMax(2600);progress.setProgress((int)Math.min(total,2600));progress.setIndicatorColor(color);progress.setTrackColor(0xff3b4350);progress.setTrackCornerRadius(dp(4));calories.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));
        content.addView(calories);gap(content,20);
        for(String section:VaultMarkdown.SECTIONS) section(section);
        content.addView(text("Листайте только существующие дни · ↻ перечитывает хранилище",12,MUTED));
        scroll.post(()->scroll.scrollTo(0,scrollY));
    }
    private void chooseDate() {
        if(busy)return;String[] labels=new String[notes.size()];int selectedIndex=0;
        for(int i=0;i<notes.size();i++) { labels[i]=notes.get(i).date.toString();if(notes.get(i).date.equals(selected)) selectedIndex=i; }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Ежедневные заметки").setSingleChoiceItems(labels,selectedIndex,(dialog,index)->{
            selected=notes.get(index).date;scrollY=0;dialog.dismiss();reload(false);
        }).setNegativeButton("Закрыть",null).show();
    }
    private void navigate(int delta) {
        if(busy)return;for(int i=0;i<notes.size();i++)if(notes.get(i).date.equals(selected)&&i+delta>=0&&i+delta<notes.size()) { selected=notes.get(i+delta).date;scrollY=0;reload(false);return; }
    }
    private void section(String name) {
        List<VaultMarkdown.Entry> entries=snapshot.day.section(name);double total=entries.stream().mapToDouble(e->e.kcal).sum();
        LinearLayout card=column();card.setPadding(dp(14),dp(8),dp(14),dp(12));card.setBackground(background(CARD,20));
        LinearLayout heading=row();TextView title=bold((expanded.contains(name)?"⌄  ":"›  ")+name,18,INK);weighted(heading,title);heading.addView(text(VaultMarkdown.fmt(total)+" ккал",14,ACCENT));
        MaterialButton add=button("＋",()->openForm(name,null));add.setTextSize(22);add.setPadding(0,0,0,0);add.setContentDescription("Добавить еду: "+name);ViewCompat.setTooltipText(add,"Добавить еду: "+name);heading.addView(add,new LinearLayout.LayoutParams(dp(48),dp(48)));heading.setMinimumHeight(dp(48));
        heading.setContentDescription(name+", "+VaultMarkdown.fmt(total)+" килокалорий, "+(expanded.contains(name)?"свернуть":"раскрыть"));
        heading.setOnClickListener(v->{scrollY=scroll.getScrollY();if(!expanded.add(name))expanded.remove(name);showDay();});card.addView(heading);
        if(expanded.contains(name)) {
            if(entries.isEmpty())card.addView(text("Пока ничего",14,MUTED));
            for(VaultMarkdown.Entry entry:entries)card.addView(entryRow(entry));
        }
        content.addView(card);gap(content,10);
    }
    private static final class EntryRow extends LinearLayout {
        EntryRow(Context context) { super(context);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL); }
        @Override public boolean performClick() { return super.performClick(); }
    }
    private View entryRow(VaultMarkdown.Entry entry) {
        LinearLayout wrapper=column();EntryRow row=new EntryRow(this);row.setPadding(dp(6),dp(12),dp(6),dp(12));
        LinearLayout texts=column();texts.addView(text(entry.name.isBlank()?"Калории":entry.name,17,INK));texts.addView(text(entry.weight==null?"Без веса":VaultMarkdown.fmt(entry.weight)+" г",13,MUTED));weighted(row,texts);row.addView(bold(VaultMarkdown.fmt(entry.kcal)+" ккал",16,INK));
        MaterialButton options=button("⋮",()->entryMenu(entry));options.setContentDescription("Действия с записью");row.addView(options,new LinearLayout.LayoutParams(dp(40),dp(48)));
        row.setContentDescription((entry.name.isBlank()?"Калории":entry.name)+", "+VaultMarkdown.fmt(entry.kcal)+" ккал. Нажмите для редактирования; смахните влево для удаления, вправо для переноса.");
        final float[] start={0,0};final boolean[] swipe={false};int slop=ViewConfiguration.get(this).getScaledTouchSlop();
        row.setOnTouchListener((v,event)->{
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {start[0]=event.getX();start[1]=event.getY();swipe[0]=false;return true;}
                case MotionEvent.ACTION_MOVE -> {
                    float dx=event.getX()-start[0],dy=event.getY()-start[1];
                    if(Math.abs(dx)>slop*2&&Math.abs(dx)>Math.abs(dy)*1.5) {swipe[0]=true;v.getParent().requestDisallowInterceptTouchEvent(true);row.setTranslationX(Math.max(-dp(60),Math.min(dp(60),dx)));}
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    float dx=event.getX()-start[0];row.animate().translationX(0).setDuration(120).start();v.getParent().requestDisallowInterceptTouchEvent(false);
                    if(swipe[0]&&Math.abs(dx)>dp(40)) {
                        if(dx>0)move(entry);
                        else delete(entry);
                    }
                    else if(Math.abs(dx)<slop&&Math.abs(event.getY()-start[1])<slop) {v.performClick();}
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {row.setTranslationX(0);return true;}
            }
            return true;
        });
        row.setOnClickListener(v->openForm(entry.section,entry));
        row.setOnLongClickListener(v->{entryMenu(entry);return true;});
        wrapper.addView(row);View divider=new View(this);divider.setBackgroundColor(0xff383f4a);wrapper.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));return wrapper;
    }
    private void entryMenu(VaultMarkdown.Entry entry) {
        if(busy)return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setItems(new String[]{"Редактировать","Переместить","Удалить"},(d,i)->{
            if(i==0)openForm(entry.section,entry);else if(i==1)move(entry);else delete(entry);
        }).show();
    }
    private void delete(VaultMarkdown.Entry entry) {
        if(busy)return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Удалить запись?").setMessage(entry.name.isBlank()?entry.canonical():entry.name).setNegativeButton("Отмена",null)
            .setPositiveButton("Удалить",(d,w)->mutate(s->VaultMarkdown.edit(s.day.source,entry,null,s.catalog))).show();
    }
    private void move(VaultMarkdown.Entry entry) {
        if(busy)return;
        List<String> targets=new ArrayList<>(VaultMarkdown.SECTIONS);targets.remove(entry.section);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Переместить в раздел").setItems(targets.toArray(String[]::new),(d,index)->mutate(s->VaultMarkdown.move(s.day.source,entry,targets.get(index),s.catalog))).show();
    }
    private interface Change { String apply(VaultRepository.Snapshot s); }
    private void mutate(Change change) {
        if(snapshot==null||busy)return;VaultRepository.Snapshot original=snapshot;scrollY=scroll.getScrollY();
        task(()->{repo.save(original,change.apply(original),null);return repo.load(original.note);},s->{snapshot=s;showDay();});
    }
    private void openForm(String section,VaultMarkdown.Entry entry) {
        if(snapshot==null||busy)return;scrollY=scroll.getScrollY();screen="form";form=new Form(snapshot,section,entry);renderForm();
    }
    private final class Form {
        final VaultRepository.Snapshot original;
        final String section;
        final VaultMarkdown.Entry entry;
        VaultMarkdown.Food selectedFood;
        boolean newFood=false,manual=false,dirty=false;
        EditText name,aliases,weight,defaultWeight,kcal100,manualKcal;
        TextView total;
        LinearLayout fields,choices;
        Form(VaultRepository.Snapshot original,String section,VaultMarkdown.Entry entry) {this.original=original;this.section=section;this.entry=entry;this.selectedFood=entry==null?null:entry.food;this.manual=entry!=null&&entry.weight==null;}
    }
    private EditText field(LinearLayout parent,String label,String value,boolean decimal,boolean enabled) {
        TextInputLayout box=new TextInputLayout(this);box.setHint(label);box.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);box.setBoxCornerRadii(dp(14),dp(14),dp(14),dp(14));box.setDefaultHintTextColor(ColorStateList.valueOf(MUTED));
        TextInputEditText edit=new TextInputEditText(box.getContext());edit.setTextColor(INK);edit.setTextSize(17);edit.setSingleLine(true);edit.setInputType(decimal?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);edit.setText(value);edit.setEnabled(enabled);
        box.addView(edit,new LinearLayout.LayoutParams(-1,-2));parent.addView(box,new LinearLayout.LayoutParams(-1,-2));gap(parent,12);return edit;
    }
    private void watch(EditText field,Runnable changed) {field.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){changed.run();}public void afterTextChanged(Editable e){}});}
    private void renderForm() {
        Form f=form;page(f.entry==null?"Добавить еду":"Редактировать",f.section+" · "+selected,false);
        MaterialButton back=button("‹  Назад к дню",this::back);content.addView(back);gap(content,12);
        LinearLayout mode=row();weighted(mode,button("Из справочника",()->{if(busy)return;f.newFood=false;showChoices();}));weighted(mode,button("＋ Новая еда",()->{if(busy)return;f.newFood=true;f.manual=false;f.selectedFood=null;showFields();}));content.addView(mode);gap(content,12);
        f.choices=column();f.fields=column();content.addView(f.choices);content.addView(f.fields);
        if(f.entry!=null)showFields();else showChoices();
    }
    private void showChoices() {
        Form f=form;if(f==null)return;f.fields.setVisibility(View.GONE);f.choices.setVisibility(View.VISIBLE);f.choices.removeAllViews();
        EditText search=field(f.choices,"Название, алиас или часть слова","",false,true);
        LinearLayout list=column();f.choices.addView(list);TextView count=text("Недавно добавленные — в начале списка",12,MUTED);f.choices.addView(count);
        Runnable fill=()->{
            String query=VaultMarkdown.key(search.getText().toString());list.removeAllViews();
            List<VaultMarkdown.Food> foods=FoodSearch.find(f.original.catalog.foods,query,
                food->prefs.getLong("recent:"+VaultMarkdown.key(food.name),0));
            int shown=0,matches=0;
            for(VaultMarkdown.Food food:foods) {
                matches++;if(shown++>=100)continue;
                LinearLayout card=column();card.setPadding(dp(14),dp(12),dp(14),dp(12));card.setBackground(background(CARD,16));card.addView(bold(food.name,17,INK));card.addView(text(VaultMarkdown.fmt(food.weight)+" г · "+VaultMarkdown.fmt(food.kcal100)+" ккал/100 г",13,MUTED));
                card.setOnClickListener(v->{if(busy)return;f.selectedFood=food;f.newFood=false;f.manual=false;hideKeyboard();showFields();});list.addView(card);gap(list,8);
            }
            if(matches==0)list.addView(text("Еда не найдена. Можно добавить новую запись.",16,MUTED));
            count.setText(matches>100?"Показаны первые 100 из "+matches+". Уточните поиск.":query.isBlank()?"Недавно добавленные — в начале списка":"Сначала точные совпадения, затем похожие");
        };
        watch(search,fill);fill.run();
    }
    private void showFields() {
        Form f=form;if(f==null)return;f.choices.setVisibility(View.GONE);f.fields.setVisibility(View.VISIBLE);f.fields.removeAllViews();
        VaultMarkdown.Food food=f.selectedFood;VaultMarkdown.Entry entry=f.entry;
        boolean unknown=entry!=null&&food==null;
        if(f.newFood) {
            f.fields.addView(button("▣  Сканировать штрихкод",()->{if(!busy)startActivityForResult(new Intent(this,ScannerActivity.class),SCAN);}));
            f.fields.addView(button("Ввести штрихкод вручную",this::manualBarcode));gap(f.fields,12);
        }
        String name=food!=null?food.name:entry!=null?entry.name:"";
        LinearLayout nameRow=row();LinearLayout nameBox=column();weighted(nameRow,nameBox);
        f.name=field(nameBox,"Название еды",name,false,food==null);
        if(food!=null) {
            MaterialButton edit=button("✎",this::editSelectedFoodInCatalog);edit.setTextSize(22);edit.setPadding(0,0,0,0);edit.setContentDescription("Редактировать продукт в справочнике");ViewCompat.setTooltipText(edit,"Редактировать в справочнике");nameRow.addView(edit,new LinearLayout.LayoutParams(dp(48),dp(52)));
        }
        f.fields.addView(nameRow);
        LinearLayout aliasBox=column();aliasBox.setVisibility(View.GONE);
        MaterialButton aliasToggle=button("Алиасы  ⌄",()->aliasBox.setVisibility(aliasBox.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));f.fields.addView(aliasToggle);
        f.aliases=field(aliasBox,"Алиасы: по одному на строке",food==null?"":String.join("\n",food.aliases),false,f.newFood);
        f.aliases.setSingleLine(false);f.aliases.setMinLines(2);f.aliases.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);f.fields.addView(aliasBox);gap(f.fields,10);
        double initialWeight=entry!=null&&entry.weight!=null?entry.weight:food!=null?food.weight:100;
        if(f.newFood)f.defaultWeight=field(f.fields,"Вес по умолчанию, г","100",true,true);
        if(!f.manual) {
            f.weight=field(f.fields,"Вес порции, г",VaultMarkdown.fmt(initialWeight),true,true);
            double kcal=food!=null?food.kcal100:entry!=null&&entry.weight!=null&&entry.weight>0?entry.kcal*100/entry.weight:0;
            f.kcal100=field(f.fields,"Калорийность на 100 г",f.newFood?"":VaultMarkdown.fmt(kcal),true,f.newFood||unknown);
        } else { f.manualKcal=field(f.fields,"Калории, ккал",VaultMarkdown.fmt(entry.kcal),true,true); }
        f.total=bold("",27,GREEN);f.fields.addView(f.total);gap(f.fields,12);
        Runnable update=()->{
            try { double total=f.manual?VaultMarkdown.number(f.manualKcal.getText().toString()):Math.round(VaultMarkdown.number(f.weight.getText().toString())*VaultMarkdown.number(f.kcal100.getText().toString())/100);
                f.total.setText(getString(R.string.portion_calories,VaultMarkdown.fmt(total)));
            } catch(Exception e) { f.total.setText("— ккал в порции"); }
        };
        if(f.manual)watch(f.manualKcal,()->{f.dirty=true;update.run();});
        else {watch(f.weight,()->{f.dirty=true;update.run();});watch(f.kcal100,()->{f.dirty=true;update.run();});}
        watch(f.name,()->f.dirty=true);watch(f.aliases,()->f.dirty=true);
        if(f.newFood)watch(f.defaultWeight,()->{f.dirty=true;});
        update.run();
        MaterialButton save=button(entry==null?"Добавить в "+f.section:"Сохранить изменения",this::saveForm);save.setBackgroundTintList(ColorStateList.valueOf(ACCENT));save.setTextColor(BG);f.fields.addView(save,new LinearLayout.LayoutParams(-1,dp(56)));
        if(f.newFood)f.fields.addView(text("Новый продукт сохранится в таблицу Еда.md и сразу добавится в выбранный раздел дневника.",13,MUTED));
        if(unknown&&!f.newFood)f.fields.addView(text("Эта запись не найдена в справочнике. Изменения сохранятся только в дневной заметке.",13,MUTED));
    }
    private void saveForm() {
        Form f=form;if(f==null||busy)return;
        try {
            if(f.entry==null&&!f.newFood&&f.selectedFood==null)throw new IllegalArgumentException("Сначала выберите еду");
            String name=f.name.getText().toString().trim();
            String line;VaultMarkdown.Food added=null;VaultMarkdown.Catalog catalog=f.original.catalog;
            if(f.manual) {
                if(name.contains("\n")||name.contains("\r")||name.contains("::")||name.contains("`")||name.contains("~~")||name.matches("^[>#|].*"))throw new IllegalArgumentException("Недопустимое название");
                line=(name.isBlank()?"":name+" ")+VaultMarkdown.fmt(VaultMarkdown.number(f.manualKcal.getText().toString()))+" ккал";
            } else {
                double grams=VaultMarkdown.number(f.weight.getText().toString()),kcal100=VaultMarkdown.number(f.kcal100.getText().toString());
                line=VaultMarkdown.portion(name,grams,kcal100);
                if(f.newFood) {
                    List<String> aliases=new ArrayList<>();for(String alias:f.aliases.getText().toString().split("\\R"))if(!alias.trim().isEmpty())aliases.add(alias.trim());
                    added=new VaultMarkdown.Food(name,aliases,"",kcal100,VaultMarkdown.number(f.defaultWeight.getText().toString()));
                    catalog=VaultMarkdown.catalog(VaultMarkdown.addFood(catalog,added,f.original.replacementsText));
                }
            }
            String updated=f.entry==null?VaultMarkdown.add(f.original.day.source,f.section,line,catalog):VaultMarkdown.edit(f.original.day.source,f.entry,line,catalog);
            final VaultMarkdown.Food newFood=added;hideKeyboard();
            task(()->{repo.save(f.original,updated,newFood);return repo.load(f.original.note);},s->{
                if(f.entry==null)prefs.edit().putLong("recent:"+VaultMarkdown.key(name),System.currentTimeMillis()).apply();snapshot=s;expanded.add(f.section);showDay();
            });
        } catch(Exception e) { error(e.getMessage());scroll.smoothScrollTo(0,0); }
    }
    private void editSelectedFoodInCatalog() {
        Form f=form;if(busy||f==null||f.selectedFood==null)return;
        Bundle draft=captureFormDraft(f);String selectedName=f.selectedFood.name;hideKeyboard();
        task(()->repo.loadCatalog(),data->{
            VaultMarkdown.Food food=data.catalog.get(selectedName);
            if(food==null){error("Продукт больше не найден в справочнике. Обновите день.");return;}
            catalogSnapshot=data;catalogReturnForm=draft;showCatalogEditor(food);
        });
    }
    private void finishCatalogEditor() {
        if(catalogReturnForm==null){showCatalog();return;}
        Bundle draft=catalogReturnForm;VaultRepository.Note note=currentNote();
        if(note==null){catalogReturnForm=null;catalogEdit=null;showDay();error("Ежедневная заметка больше не найдена.");return;}
        task(()->repo.load(note),data->{
            snapshot=data;catalogReturnForm=null;catalogEdit=null;pendingDraft=draft;showDay();restoreDraft();
        });
    }
    private void openCatalog() {
        if(repo==null||busy)return;
        screen="catalog";form=null;catalogEdit=null;page("Справочник","Еда.md",true);
        task(()->repo.loadCatalog(),data->{
            catalogSnapshot=data;showCatalog();
            if(pendingCatalogDraft!=null)restoreCatalogDraft();
        });
    }
    private void showCatalog() {
        screen="catalog";form=null;catalogEdit=null;page("Справочник","Еда.md",true);
        content.addView(button("＋ Новая запись",()->{if(!busy)showCatalogEditor(null);}));gap(content,12);
        EditText search=field(content,"Найти название или алиас",catalogQuery,false,true);
        LinearLayout list=column();content.addView(list);
        Runnable fill=()->{
            catalogQuery=search.getText().toString();list.removeAllViews();
            List<VaultMarkdown.Food> foods=FoodSearch.find(catalogSnapshot.catalog.foods,catalogQuery,f->0);
            if(foods.isEmpty())list.addView(text(catalogSnapshot.catalog.foods.isEmpty()?"Справочник пуст. Добавьте первую запись.":"Ничего не найдено",16,MUTED));
            int shown=0;for(VaultMarkdown.Food food:foods) {
                if(shown++>=100)break;list.addView(catalogRow(food));gap(list,10);
            }
            if(foods.size()>100)list.addView(text("Показаны первые 100 записей. Уточните поиск.",13,MUTED));
        };
        watch(search,fill);fill.run();
    }
    private View catalogRow(VaultMarkdown.Food food) {
        LinearLayout wrapper=column();wrapper.setBackground(background(CARD,18));wrapper.setPadding(dp(12),dp(6),dp(12),dp(6));
        EntryRow item=new EntryRow(this);LinearLayout labels=column();labels.addView(bold(food.name,18,INK));
        labels.addView(text(VaultMarkdown.fmt(food.kcal100)+" ккал/100 г · "+VaultMarkdown.fmt(food.weight)+" г",13,MUTED));
        if(!food.aliases.isEmpty())labels.addView(text(String.join(", ",food.aliases),12,MUTED));
        if(!food.ingredients.isEmpty())labels.addView(text("Составное блюдо",12,ACCENT));weighted(item,labels);
        MaterialButton more=button("⋮",()->{
            if(busy)return;
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setItems(new String[]{"Редактировать","Удалить"},(d,index)->{
                if(index==0)showCatalogEditor(food);else deleteCatalogFood(food);
            }).show();
        });more.setContentDescription("Действия с продуктом");item.addView(more,new LinearLayout.LayoutParams(dp(44),dp(48)));
        item.setContentDescription(food.name+". Нажмите для редактирования или смахните для удаления.");
        float[] down={0,0};boolean[] swipe={false};int slop=ViewConfiguration.get(this).getScaledTouchSlop();
        item.setOnTouchListener((v,event)->{
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {down[0]=event.getX();down[1]=event.getY();swipe[0]=false;return true;}
                case MotionEvent.ACTION_MOVE -> {
                    float dx=event.getX()-down[0],dy=event.getY()-down[1];
                    if(Math.abs(dx)>slop*2&&Math.abs(dx)>Math.abs(dy)*1.5) {swipe[0]=true;v.getParent().requestDisallowInterceptTouchEvent(true);item.setTranslationX(Math.max(-dp(50),Math.min(dp(50),dx)));}return true;
                }
                case MotionEvent.ACTION_UP -> {
                    float dx=event.getX()-down[0];item.animate().translationX(0).setDuration(120).start();v.getParent().requestDisallowInterceptTouchEvent(false);
                    if(swipe[0]&&Math.abs(dx)>dp(40))deleteCatalogFood(food);
                    else if(Math.abs(dx)<slop&&Math.abs(event.getY()-down[1])<slop)v.performClick();return true;
                }
                case MotionEvent.ACTION_CANCEL -> {item.setTranslationX(0);return true;}
            }return true;
        });
        item.setOnClickListener(v->{if(!busy)showCatalogEditor(food);});wrapper.addView(item);return wrapper;
    }
    private void deleteCatalogFood(VaultMarkdown.Food food) {
        if(busy)return;VaultRepository.CatalogSnapshot original=catalogSnapshot;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Удалить продукт?").setMessage(food.name).setNegativeButton("Отмена",null)
            .setPositiveButton("Удалить",(d,w)->task(()->{String updated=VaultMarkdown.deleteFood(original.catalog,food.name);repo.saveCatalog(original,updated);return repo.loadCatalog();},data->{catalogSnapshot=data;showCatalog();})).show();
    }
    private final class CatalogEdit {
        final VaultRepository.CatalogSnapshot original;
        final VaultMarkdown.Food food;
        EditText name,aliases,weight,kcal100;
        List<RecipeIngredients.Item> ingredients;
        LinearLayout ingredientBody,ingredientList;
        MaterialButton ingredientToggle;
        boolean ingredientsExpanded;
        TextView calculation;
        boolean dirty,updating;
        CatalogEdit(VaultRepository.CatalogSnapshot original,VaultMarkdown.Food food){this.original=original;this.food=food;this.ingredients=RecipeIngredients.parse(original.catalog,food==null?"":food.ingredients);}
    }
    private List<String> aliasLines(String value){List<String> names=new ArrayList<>();for(String line:value.split("\\R"))if(!line.isBlank())names.add(line.trim());return names;}
    private void showCatalogEditor(VaultMarkdown.Food food) {
        pendingCatalogBarcode=null;
        screen="catalog-edit";form=null;CatalogEdit f=new CatalogEdit(catalogSnapshot,food);catalogEdit=f;
        page(food==null?"Новый продукт":"Изменить продукт","Справочник еды",true);
        f.name=field(content,"Название",food==null?"":food.name,false,true);
        f.aliases=field(content,"Алиасы: по одному на строке",food==null?"":String.join("\n",food.aliases),false,true);
        f.aliases.setSingleLine(false);f.aliases.setMinLines(2);f.aliases.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        f.weight=field(content,"Вес по умолчанию, г",food==null?"100":VaultMarkdown.fmt(food.weight),true,true);
        f.kcal100=field(content,"Калорийность на 100 г",food==null?"":VaultMarkdown.fmt(food.kcal100),true,true);
        f.ingredientToggle=button("›  Ингредиенты",()->{f.ingredientsExpanded=!f.ingredientsExpanded;renderIngredients(f);});
        content.addView(f.ingredientToggle,new LinearLayout.LayoutParams(-1,dp(52)));
        f.ingredientBody=column();f.ingredientBody.setPadding(dp(12),dp(4),dp(12),dp(12));f.ingredientBody.setBackground(background(CARD,18));
        f.ingredientList=column();f.ingredientBody.addView(f.ingredientList);
        f.ingredientBody.addView(button("＋ Добавить из справочника",()->chooseIngredient(f)));
        f.ingredientBody.addView(text("Выберите продукты и их вес. Вес блюда и калорийность рассчитаются автоматически.",13,MUTED));
        content.addView(f.ingredientBody);gap(content,12);renderIngredients(f);
        f.calculation=text("",16,ACCENT);content.addView(f.calculation);gap(content,12);
        MaterialButton save=button("Сохранить",this::saveCatalogEditor);save.setBackgroundTintList(ColorStateList.valueOf(ACCENT));save.setTextColor(BG);content.addView(save,new LinearLayout.LayoutParams(-1,dp(56)));
        if(food!=null)content.addView(text("При переименовании прежнее название сохраняется как алиас для записей дневника. Изменение справочника не переписывает прошлые заметки.",13,MUTED));
        Runnable changed=()->{if(f.updating)return;f.dirty=true;catalogCalculation(f);};
        for(EditText edit:List.of(f.name,f.aliases,f.weight,f.kcal100))watch(edit,changed);
        catalogCalculation(f);f.dirty=false;
    }
    private void renderIngredients(CatalogEdit f) {
        f.ingredientToggle.setText((f.ingredientsExpanded?"⌄  ":"›  ")+"Ингредиенты"+(f.ingredients.isEmpty()?"":" ("+f.ingredients.size()+")"));
        f.ingredientBody.setVisibility(f.ingredientsExpanded?View.VISIBLE:View.GONE);f.ingredientList.removeAllViews();
        if(f.ingredients.isEmpty())f.ingredientList.addView(text("Добавьте продукты, чтобы собрать составное блюдо.",14,MUTED));
        for(int i=0;i<f.ingredients.size();i++) {
            int index=i;RecipeIngredients.Item item=f.ingredients.get(i);LinearLayout line=row();LinearLayout labels=column();labels.addView(text(item.name(),16,INK));
            VaultMarkdown.Food food=f.original.catalog.get(item.name());
            if(food!=null)labels.addView(text(Math.round(food.kcal100*item.grams()/100)+" ккал",12,MUTED));weighted(line,labels);
            MaterialButton weight=button(VaultMarkdown.fmt(item.grams())+" г",()->ingredientWeight(f,null,index));weight.setContentDescription("Изменить вес: "+item.name());line.addView(weight);
            MaterialButton remove=button("×",()->{if(busy)return;f.ingredients.remove(index);f.dirty=true;renderIngredients(f);catalogCalculation(f);});remove.setContentDescription("Убрать ингредиент "+item.name());line.addView(remove,new LinearLayout.LayoutParams(dp(44),dp(48)));f.ingredientList.addView(line);
        }
    }
    private void chooseIngredient(CatalogEdit f) {
        if(busy||catalogEdit!=f)return;
        LinearLayout body=column();body.setPadding(dp(20),dp(8),dp(20),dp(8));
        EditText search=field(body,"Найти продукт или блюдо","",false,true);
        ScrollView results=new ScrollView(this);LinearLayout list=column();results.addView(list);body.addView(results,new LinearLayout.LayoutParams(-1,dp(300)));
        androidx.appcompat.app.AlertDialog dialog=new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Добавить ингредиент").setView(body).setNegativeButton("Отмена",null).create();
        List<VaultMarkdown.Food> available=new ArrayList<>();
        for(VaultMarkdown.Food food:f.original.catalog.foods)if(RecipeIngredients.available(f.original.catalog,food,f.food==null?null:f.food.name))available.add(food);
        Runnable fill=()->{
            list.removeAllViews();List<VaultMarkdown.Food> matches=FoodSearch.find(available,search.getText().toString(),food->prefs.getLong("recent:"+VaultMarkdown.key(food.name),0));
            int shown=0;for(VaultMarkdown.Food food:matches) {
                if(shown++>=100)break;
                LinearLayout option=column();option.setPadding(dp(10),dp(10),dp(10),dp(10));option.addView(bold(food.name,16,INK));
                option.addView(text(VaultMarkdown.fmt(food.kcal100)+" ккал/100 г"+(food.ingredients.isEmpty()?"":" · составное блюдо"),12,MUTED));
                option.setOnClickListener(v->{dialog.dismiss();ingredientWeight(f,food,-1);});list.addView(option);
            }
            if(matches.isEmpty())list.addView(text(available.isEmpty()?"Нет подходящих продуктов. Сначала добавьте их в справочник.":"Ничего не найдено",14,MUTED));
            if(matches.size()>100)list.addView(text("Уточните поиск: показаны первые 100 записей",12,MUTED));
        };
        watch(search,fill);fill.run();dialog.show();
    }
    private void ingredientWeight(CatalogEdit f,VaultMarkdown.Food selected,int index) {
        if(busy||catalogEdit!=f)return;
        LinearLayout body=column();body.setPadding(dp(20),dp(8),dp(20),dp(8));
        String name=selected==null?f.ingredients.get(index).name():selected.name;
        double initial=selected==null?f.ingredients.get(index).grams():selected.weight;
        EditText weight=field(body,"Вес, г",VaultMarkdown.fmt(initial),true,true);
        if(selected!=null&&!selected.ingredients.isEmpty())body.addView(text("Блюдо будет разложено на простые ингредиенты пропорционально указанному весу.",13,MUTED));
        androidx.appcompat.app.AlertDialog dialog=new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(name).setView(body).setNegativeButton("Отмена",null).setPositiveButton(selected==null?"Применить":"Добавить",null).create();
        dialog.setOnShowListener(d->dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{
            if(busy||catalogEdit!=f)return;
            try {
                double grams=RecipeIngredients.grams(VaultMarkdown.number(weight.getText().toString()));
                if(selected==null)f.ingredients.set(index,new RecipeIngredients.Item(name,grams));
                else f.ingredients=RecipeIngredients.add(f.original.catalog,f.ingredients,selected.name,grams,f.food==null?null:f.food.name);
                f.dirty=true;f.ingredientsExpanded=true;renderIngredients(f);catalogCalculation(f);dialog.dismiss();
            }catch(Exception error){weight.setError(error.getMessage());}
        }));dialog.show();
    }
    private VaultMarkdown.Food catalogInput(CatalogEdit f) {
        String ingredients=RecipeIngredients.toMarkdown(f.ingredients);
        return new VaultMarkdown.Food(f.name.getText().toString(),aliasLines(f.aliases.getText().toString()),ingredients,
            ingredients.isEmpty()?VaultMarkdown.number(f.kcal100.getText().toString()):0,
            ingredients.isEmpty()?VaultMarkdown.number(f.weight.getText().toString()):0);
    }
    private void catalogCalculation(CatalogEdit f) {
        boolean recipe=!f.ingredients.isEmpty();f.weight.setEnabled(!recipe);f.kcal100.setEnabled(!recipe);
        if(f.name.getText().toString().isBlank()||!recipe&&f.kcal100.getText().toString().isBlank()) {f.calculation.setText("Заполните название и калорийность продукта");f.calculation.setTextColor(MUTED);return;}
        try {
            VaultMarkdown.Food input=catalogInput(f);
            String updated=VaultMarkdown.putFood(f.original.catalog,f.food==null?null:f.food.name,input,f.original.replacementsText);
            VaultMarkdown.Food calculated=VaultMarkdown.catalog(updated).get(input.name);
            if(recipe){f.updating=true;f.weight.setText(VaultMarkdown.fmt(calculated.weight));f.kcal100.setText(VaultMarkdown.fmt(calculated.kcal100));f.updating=false;}
            f.calculation.setText("Порция по умолчанию: "+Math.round(calculated.weight*calculated.kcal100/100)+" ккал");f.calculation.setTextColor(ACCENT);
        }catch(Exception e){f.updating=false;f.calculation.setText(e.getMessage());f.calculation.setTextColor(MUTED);}
    }
    private void saveCatalogEditor() {
        CatalogEdit f=catalogEdit;if(f==null||busy)return;
        try {
            VaultMarkdown.Food input=catalogInput(f);String updated=VaultMarkdown.putFood(f.original.catalog,f.food==null?null:f.food.name,input,f.original.replacementsText);hideKeyboard();
            task(()->{repo.saveCatalog(f.original,updated);return repo.loadCatalog();},data->{
                if(f.food!=null&&!VaultMarkdown.key(f.food.name).equals(VaultMarkdown.key(input.name)))prefs.edit().putLong("recent:"+VaultMarkdown.key(input.name),prefs.getLong("recent:"+VaultMarkdown.key(f.food.name),0)).remove("recent:"+VaultMarkdown.key(f.food.name)).apply();
                catalogSnapshot=data;finishCatalogEditor();
            });
        }catch(Exception e){error(e.getMessage());}
    }
    private void returnToDay() {
        catalogEdit=null;showDay();if(snapshot!=null)reload(false);
    }
    private void restoreCatalogDraft() {
        Bundle draft=pendingCatalogDraft;pendingCatalogDraft=null;
        if(draft.getInt("hash")!=catalogSnapshot.foodText.hashCode()){pendingCatalogBarcode=null;catalogReturnForm=null;error("Справочник изменился после закрытия формы. Откройте продукт заново.");return;}
        String name=draft.getString("oldName");VaultMarkdown.Food old=name==null?null:catalogSnapshot.catalog.get(name);
        if(name!=null&&old==null){pendingCatalogBarcode=null;catalogReturnForm=null;error("Продукт больше не найден в справочнике.");return;}
        String scannedCode=pendingCatalogBarcode;
        showCatalogEditor(old);pendingCatalogBarcode=scannedCode;CatalogEdit f=catalogEdit;f.updating=true;
        f.name.setText(draft.getString("name",""));f.aliases.setText(draft.getString("aliases",""));f.weight.setText(draft.getString("weight",""));f.kcal100.setText(draft.getString("kcal100",""));f.ingredients=RecipeIngredients.parse(f.original.catalog,draft.getString("ingredients",""));f.ingredientsExpanded=draft.getBoolean("ingredientsExpanded",false);renderIngredients(f);
        f.updating=false;catalogCalculation(f);f.dirty=true;consumeCatalogBarcode();
    }
    private void consumeCatalogBarcode() {
        if(pendingCatalogBarcode==null||catalogEdit==null||!screen.equals("catalog-edit")||busy)return;
        // Retain the code during the request so recreated Activities can resume the lookup.
        String code=pendingCatalogBarcode;CatalogEdit f=catalogEdit;
        task(()->ProductLookup.find(code),product->{
            if(code.equals(pendingCatalogBarcode))pendingCatalogBarcode=null;
            if(catalogEdit!=f||!screen.equals("catalog-edit"))return;
            f.updating=true;
            if(!product.name().isBlank())f.name.setText(product.name());
            if(product.kcal100()!=null&&f.ingredients.isEmpty())f.kcal100.setText(VaultMarkdown.fmt(product.kcal100()));
            f.updating=false;f.dirty=true;catalogCalculation(f);
            if(!f.ingredients.isEmpty())toast("Название заполнено из Open Food Facts. Калорийность блюда рассчитывается по ингредиентам.");
            else if(product.kcal100()==null)toast("Калорийность не найдена — укажите её по упаковке.");
            else toast("Данные из Open Food Facts. Проверьте их по упаковке.");
        });
    }
    private void manualBarcode() {manualBarcode(this::lookup);}
    private void manualBarcode(Consumer<String> result) {
        if(busy)return;EditText code=new EditText(this);code.setInputType(InputType.TYPE_CLASS_NUMBER);code.setHint("Штрихкод упаковки");
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Поиск товара").setView(code).setNegativeButton("Отмена",null).setPositiveButton("Найти",(d,w)->result.accept(code.getText().toString().trim())).show();
    }
    private void lookup(String code) {
        Form f=form;if(f==null||!f.newFood||busy)return;
        task(()->ProductLookup.find(code),product->{
            if(form!=f||!f.newFood||f.selectedFood!=null)return;
            f.name.setText(product.name());if(product.kcal100()!=null)f.kcal100.setText(VaultMarkdown.fmt(product.kcal100()));
            f.dirty=true;toast(product.kcal100()==null?"Название найдено, калорийность отсутствует — укажите её по упаковке":"Данные из Open Food Facts. Проверьте их по упаковке.");
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;
        if(request==PICK_VAULT&&data.getData()!=null) {
            Uri uri=data.getData();
            try {
                int granted=data.getFlags();
                if((granted&Intent.FLAG_GRANT_READ_URI_PERMISSION)==0||(granted&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0) throw new SecurityException("Нужен доступ и на чтение, и на запись");
                getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                connect(uri,true);
            }
            catch(Exception e){error("Не удалось сохранить доступ к папке: "+e.getMessage());}
        } else if(request==SCAN_CATALOG) {pendingCatalogBarcode=data.getStringExtra("barcode");consumeCatalogBarcode();}
        else if(request==SCAN) {String code=data.getStringExtra("barcode");if(code!=null)lookup(code);}
        else if(request==EXPORT&&data.getData()!=null&&repo!=null) {Uri uri=data.getData();task(()->{repo.exportBackups(uri);return true;},v->toast("Резервные копии экспортированы"));}
    }
    private void hideKeyboard() { View focus=getCurrentFocus();if(focus!=null)((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0); }
    private void back() {
        if(busy){toast("Дождитесь завершения операции");return;}
        if(screen.equals("catalog-edit")) {
            if(catalogEdit!=null&&catalogEdit.dirty)new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Выйти без сохранения?").setNegativeButton("Остаться",null).setPositiveButton("Выйти",(d,w)->finishCatalogEditor()).show();
            else finishCatalogEditor();
        } else if(screen.equals("catalog"))returnToDay();
        else if(screen.equals("form")) {
            if(form!=null&&form.dirty)new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Выйти без сохранения?").setNegativeButton("Остаться",null).setPositiveButton("Выйти",(d,w)->showDay()).show();
            else showDay();
        } else if(screen.equals("settings")&&repo!=null)showDay();else finish();
    }
}
