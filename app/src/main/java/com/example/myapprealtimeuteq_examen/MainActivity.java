package com.example.myapprealtimeuteq_examen;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.example.myapprealtimeuteq_examen.camerart.CameraConnectionFragment;
import com.example.myapprealtimeuteq_examen.camerart.ImageUtils;
import com.example.myapprealtimeuteq_examen.ml.Model;
import com.google.android.gms.tasks.OnFailureListener;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class MainActivity extends AppCompatActivity
        implements
        OnFailureListener,
        ImageReader.OnImageAvailableListener {

    Permisos permisos;
    ArrayList<String> permisosNoAprobados;
    Button btnCamara;
    public TextView txtResults;
    int TamañoImage = 224;

    TextToSpeech textToSpeech;
    Boolean Estado ;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnCamara = findViewById(R.id.btnCamera);
        txtResults = findViewById(R.id.txtresults);

        ArrayList<String> permisos_requeridos = new ArrayList<String>();
        permisos_requeridos.add(android.Manifest.permission.CAMERA);
        permisos_requeridos.add(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE);
        permisos_requeridos.add(Manifest.permission.READ_EXTERNAL_STORAGE);

        permisos = new Permisos(this);

        permisosNoAprobados = permisos.getPermisosNoAprobados(permisos_requeridos);

        requestPermissions(permisosNoAprobados.toArray(new String[permisosNoAprobados.size()]),
                100);

        Estado = true;

        //instanciar el text to speech
        textToSpeech = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status==TextToSpeech.SUCCESS)
                {
                    textToSpeech.setLanguage(new Locale("es", "ES"));
                    textToSpeech.setSpeechRate(1.0f);

                    //Monitorear el progreso de síntesis de habla
                    textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {
                            // La síntesis de habla ha comenzado
                            Estado = false;
                        }

                        @Override
                        public void onDone(String utteranceId) {
                            // La síntesis de habla ha terminado
                            Estado = true;
                        }

                        @Override
                        public void onError(String utteranceId) {
                            // Se ha producido un error durante la síntesis de habla
                        }
                    });
                }
            }
        });
    }

    //Mostrar imagen en tiempo real

    int previewHeight = 0, previewWidth = 0;
    int sensorOrientation;

    CameraConnectionFragment camera2Fragment;

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    protected void setFragment() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String cameraId = null;
        try {
            cameraId = manager.getCameraIdList()[0];
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        CameraConnectionFragment fragment;
        camera2Fragment =
                CameraConnectionFragment.newInstance(
                        (CameraConnectionFragment.ConnectionCallback) new CameraConnectionFragment.ConnectionCallback() {
                            @Override
                            public void onPreviewSizeChosen(final Size size, final int rotation) {
                                previewHeight = size.getHeight();
                                previewWidth = size.getWidth();
                                sensorOrientation = rotation - getScreenOrientation();
                            }
                        },
                        this,
                        R.layout.fragmentcamera,
                        new Size(640, 480));

        camera2Fragment.setCamera(cameraId);
        fragment = camera2Fragment;
        getFragmentManager().beginTransaction().replace(R.id.container, fragment).commit();
    }

    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            default:
                return 0;
        }
    }

    public void abrircamara(View view) {
        setFragment();
    }


    //Obtener fotogramas de la grabación de la cámara en directo

    private boolean isProcessingFrame = false;
    private byte[][] yuvBytes = new byte[3][];
    private int[] rgbBytes = null;
    private int yRowStride;
    private Runnable postInferenceCallback;
    private Runnable imageConverter;
    private Bitmap rgbFrameBitmap;

    @Override
    public void onImageAvailable(ImageReader reader) {
        if (previewWidth == 0 || previewHeight == 0) return;
        if (rgbBytes == null) rgbBytes = new int[previewWidth * previewHeight];
        try {
            final Image image = reader.acquireLatestImage();
            if (image == null) return;
            if (isProcessingFrame) {
                image.close();
                return;
            }

            isProcessingFrame = true;
            final Image.Plane[] planes = image.getPlanes();
            fillBytes(planes, yuvBytes);
            yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();
            imageConverter =  new Runnable() {
                @Override
                public void run() {
                    ImageUtils.convertYUV420ToARGB8888( yuvBytes[0], yuvBytes[1], yuvBytes[2], previewWidth,  previewHeight,
                            yRowStride,uvRowStride, uvPixelStride,rgbBytes);
                }
            };
            postInferenceCallback =      new Runnable() {
                @Override
                public void run() {  image.close(); isProcessingFrame = false;  }
            };

            processImage();

        } catch (final Exception e) {
        }
    }

    protected void fillBytes(final Image.Plane[] planes, final byte[][] yuvBytes) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }

    @Override
    public void onFailure(@NonNull Exception e) {

    }
    //Procesar la imagen con el modelo

    private void processImage() {
        imageConverter.run();

        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        rgbFrameBitmap.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight);
        try {
            classifyImage(rgbFrameBitmap);
        } catch (Exception e) {
            txtResults.setText("Error al enviar la imagen");
            Log.i("error imagen", "processImage: " + e.getMessage());
        }
        postInferenceCallback.run();

    }

    //modelo Procesar la imagenes con el modelo

    public void classifyImage(Bitmap image){
        try {
            Model model = Model.newInstance(getApplicationContext());
            image = Bitmap.createScaledBitmap(image, TamañoImage, TamañoImage, true);

            // Creates inputs for reference.
            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, TamañoImage, TamañoImage, 3}, DataType.FLOAT32);
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * TamañoImage * TamañoImage * 3);
            byteBuffer.order(ByteOrder.nativeOrder());

            // get 1D array of 224 * 224 pixels in image
            int [] intValues = new int[TamañoImage * TamañoImage];
            image.getPixels(intValues, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());

            // iterate over pixels and extract R, G, and B values. Add to bytebuffer.
            int pixel = 0;
            for(int i = 0; i < image.getHeight(); i++){
                for(int j = 0; j <  image.getWidth(); j++){
                    int val = intValues[pixel++]; // RGB
                    byteBuffer.putFloat(((val >> 16) & 0xFF) * (1.f / 255.f));
                    byteBuffer.putFloat(((val >> 8) & 0xFF) * (1.f / 255.f));
                    byteBuffer.putFloat((val & 0xFF) * (1.f / 255.f));
                }
            }

            inputFeature0.loadBuffer(byteBuffer);

            // Runs model inference and gets result.
            Model.Outputs outputs = model.process(inputFeature0);
            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();

            float[] confidences = outputFeature0.getFloatArray();
            // find the index of the class with the biggest confidence.
            int maxPos = 0;
            float maxConfidence = 0;
            for(int i = 0; i < confidences.length; i++){
                if(confidences[i] > maxConfidence){
                    maxConfidence = confidences[i];
                    maxPos = i;
                }
            }
            final String[] classes = {
                    "Auditorio",
                    "Biblioteca",
                    "Centro Medico" ,
                    "Departamento Academico" ,
                    "Comedor secundario" ,
                    "Departamento de archivos" ,
                    "Facultad de Ciencias Pedagogicas" ,
                    "Facultad de Ciencias de la Salud" ,
                    "Facultad de Ciencias Sociales Economicas Y Financiera" ,
                    "Facultad de Ciencias Empresariales" ,
                    "Parqueadero" ,
                    "Instituto de informatica" ,
                    "Polideportivo" ,
                    "Rectorado" ,
                    "Rotonda" ,
                    "Departamento de Investigacion",
                    "Comedor principal"};


            String resultado = classes[maxPos];

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    txtResults.setText(resultado);
                }
            });

            //invocar el text to speech
            if (Estado){
                if (textToSpeech != null) {
                    String frase= resultado;
                    String utteranceId = "FraseUnica";
                    HashMap<String, String> params = new HashMap<>();
                    params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);

                    textToSpeech.speak(frase , TextToSpeech.QUEUE_FLUSH, params);
                    Log.d("AQUi", resultado);
                }
            }

            model.close();
        } catch (Exception e) {
            //txtResults.setText("error");
            Log.i("error",e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }

}