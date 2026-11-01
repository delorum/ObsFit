package ru.obsfit;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.*;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScannerActivity extends AppCompatActivity {
    private final ExecutorService analysis=Executors.newSingleThreadExecutor();
    private final AtomicBoolean found=new AtomicBoolean();
    private PreviewView preview;
    private ProcessCameraProvider provider;
    private BarcodeScanner scanner;
    private TextView hint;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setBackgroundColor(0xff1e232a);
        layout.setPadding(20,20,20,20);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(layout,(v,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left+20,bars.top+20,bars.right+20,bars.bottom+20);return insets;
        });
        TextView title=new TextView(this);title.setText("Сканирование штрихкода");title.setTextSize(23);title.setTextColor(0xffe6e7ec);layout.addView(title);
        hint=new TextView(this);hint.setText("Наведите камеру на штрихкод упаковки");hint.setTextColor(0xffa0abbd);hint.setPadding(0,16,0,20);layout.addView(hint);
        preview=new PreviewView(this);layout.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        Button cancel=new Button(this);cancel.setText("Назад");cancel.setOnClickListener(v->finish());layout.addView(cancel);setContentView(layout);
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA},17);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==17&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED) startCamera();
        else { hint.setText("Нет доступа к камере. Можно ввести штрихкод вручную на предыдущем экране."); }
    }
    @OptIn(markerClass=ExperimentalGetImage.class)
    private void startCamera() {
        scanner=BarcodeScanning.getClient(new BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13,Barcode.FORMAT_EAN_8,Barcode.FORMAT_UPC_A,Barcode.FORMAT_UPC_E,Barcode.FORMAT_CODE_128).build());
        ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{
            if(isFinishing()||isDestroyed()) return;
            try {
                provider=future.get();
                Preview cameraPreview=new Preview.Builder().build();cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis images=new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                images.setAnalyzer(analysis,image->{
                    if(found.get()||image.getImage()==null) { image.close();return; }
                    InputImage input=InputImage.fromMediaImage(image.getImage(),image.getImageInfo().getRotationDegrees());
                    scanner.process(input).addOnSuccessListener(barcodes->{
                        for(Barcode barcode:barcodes) {
                            String value=barcode.getRawValue();
                            if(value!=null&&value.matches("\\d{8,14}")&&found.compareAndSet(false,true)) {
                                setResult(RESULT_OK,new Intent().putExtra("barcode",value));finish();break;
                            }
                        }
                    }).addOnCompleteListener(task->image.close());
                });
                provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,cameraPreview,images);
            } catch(Exception error) { hint.setText(getString(R.string.camera_error,error.getMessage())); }
        },ContextCompat.getMainExecutor(this));
    }
    @Override public void onDestroy() {
        if(provider!=null) provider.unbindAll();analysis.shutdown();if(scanner!=null) scanner.close();super.onDestroy();
    }
}
