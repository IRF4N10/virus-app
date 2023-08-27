package com.example.autocamera2;

import android.Manifest;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.icu.text.SimpleDateFormat;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Stack;

/**
 * Service allow take photo running in the background
 */
public class TakePhotoService extends Service {
    private static final String TAG = "TakePhotoService";
    public static final String INTENT_FILTER = "INTENT_FILTER";
    public static final int ALARM_THRESH = 130;
    public static final int BYTE_MULTI_FACTOR = 1024;
    public static final int SEC_TO_MILLI = 1000;
    public static final String TEST_RESULT = "TEST_RESULT";
    private final IBinder mBinder = new MyBinder();
    private Handler mHandler;
    private Boolean mIsPaused;
    private int mSensorOrientation;
    private CameraDevice cameraDevice;
    private File file;
    private Context mContext;
    private int interval;
    private int alarmThreshHold;
    private Size imageDimension;
    private MediaPlayer mp;
    private Rect detectArea;
    private long curStorage;


    @Override
    public void onCreate() {
        super.onCreate();
        mIsPaused = true;
        HandlerThread mHandlerThread = new HandlerThread("Camera Background");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mContext = getApplicationContext();
        interval = 60000;
        detectArea = null;
        curStorage = checkStorage();
        alarmThreshHold = 0;
        //prevImg = null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public class MyBinder extends Binder {

        TakePhotoService getService() {
            return TakePhotoService.this;
        }
    }

    public void setInterval(int interval) {
        this.interval = interval;
    }

    public void setAlarmThreshHold (int alarmThreshHold){this.alarmThreshHold = alarmThreshHold; }

    public void pauseTask() {
        mIsPaused = true;
    }

    public void unPauseTask() {
        mIsPaused = false;
        startTask();
    }

    /**
     * Start taking photo with interval
     */
    public void startTask() {
        final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (mIsPaused) {
                    Log.d(TAG, "run: removing callbacks");
                    if (mp != null) {
                        mp.release();
                    }
                    mHandler.removeCallbacks(this);
                } else {
                    Log.d(TAG, "running");
                    takePicture();
                    mHandler.postDelayed(this, interval);
                }
            }
        };
        mHandler.postDelayed(runnable, 100);
    }

    /**
     * Opens camera and sets up image dimension
     */
    public void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.e(TAG, "opening camera");
            if(MainActivity.frontBack == true) {
                try {
                    String cameraId = manager.getCameraIdList()[1];
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                    StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    assert map != null;
                    imageDimension = new Size(1280, 960);
                    if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    showToast("Please grant permissions before starting the service.");
                    } else {
                    manager.openCamera(cameraId, stateCallback, null);
                    }
                } catch (CameraAccessException e) {
                e.printStackTrace();
                }
            }
            else{
                try {
                    String cameraId = manager.getCameraIdList()[0];
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                    StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    assert map != null;
                    imageDimension = new Size(1280, 960);
                    if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        showToast("Please grant permissions before starting the service.");
                    } else {
                        manager.openCamera(cameraId, stateCallback, null);
                    }
                } catch (CameraAccessException e) {
                e.printStackTrace();
                }
            }
    }

    /**
     * Callback function of camera device, sends a broadcast on camera opened
     */
    CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            Intent intent = new Intent(INTENT_FILTER);
            sendBroadcast(intent);
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            cameraDevice.close();
        }
    };

    /**
     * Takes picture and save it with regard to the rotation and analyzes picture taken
     */
    public void takePicture() {
        if (cameraDevice == null)
            return;
        try {
            //Capture image with custom size
            int width = imageDimension.getWidth();
            int height = imageDimension.getHeight();

            ImageReader mImageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2);
            List<Surface> outputSurfaces = new LinkedList<>();
            outputSurfaces.add(mImageReader.getSurface());

            long limitB = getStorageLimit();
            long exposure = getExposureTime();
            long iso = getISO();

            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            if (exposure != -1) {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraCharacteristics.CONTROL_AE_MODE_OFF);
                captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
            }
            if (iso != -1) {
                setIso(captureBuilder, (int) iso); // Set ISO value
            }


            ImageReader.OnImageAvailableListener readerListener = reader -> {
                try (Image image = reader.acquireLatestImage()) {

                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.capacity()];
                    buffer.get(bytes);
                    Bitmap bitmapImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, null);
                    bitmapImage = rotateBitmap(bitmapImage, mSensorOrientation);
                    boolean result = analyzeImage(bitmapImage);

                    if (limitB != -1 && curStorage >= limitB) {
                        if (delete(limitB)) {
                            Log.d(TAG, "delete photo succeeded");
                        } else {
                            Log.d(TAG, "delete photo failed");
                        }
                    }

                    save(bitmapImage, result, exposure,iso);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            };
            mImageReader.setOnImageAvailableListener(readerListener, mHandler);

            CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Intent intent = new Intent(INTENT_FILTER);
                    sendBroadcast(intent);
                }
            };

            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    try {
                        cameraCaptureSession.capture(captureBuilder.build(), captureListener, mHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {

                }
            }, mHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Get storage limit from settings
     * @return storage limit in bytes
     */
    private long getStorageLimit() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String limit = prefs.getString("Storage Limit", "None");
        long limitB;
        if (TextUtils.isDigitsOnly(limit)){
            limitB = Integer.parseInt(limit) * BYTE_MULTI_FACTOR * BYTE_MULTI_FACTOR;
        } else {
            limitB = -1;
        }
        return limitB;
    }

    /**
     * Get exposure time from settings
     * @return exposure time in nano seconds
     */
    private long getExposureTime() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String limit = prefs.getString("Exposure Time", "default");
        long exposure;
        if (TextUtils.isDigitsOnly(limit)){
            if (Integer.parseInt(limit) > SEC_TO_MILLI) {
                exposure = SEC_TO_MILLI * SEC_TO_MILLI * SEC_TO_MILLI;
            } else {
                exposure = Integer.parseInt(limit) * SEC_TO_MILLI * SEC_TO_MILLI;
            }
        } else {
            exposure = -1;
        }
        return exposure;
    }
    private long getISO() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String limit = prefs.getString("ISO level", "Default");
        long ISO;
        String mCameraId = null;
        CameraManager cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        try {
            // Get the list of available camera devices
            String[] cameraIds = cameraManager.getCameraIdList();

            // Choose the first camera device
            if (cameraIds.length > 0) {
                mCameraId = cameraIds[0];
            } else {
                // No camera devices available
                // Handle the error accordingly
            }
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(mCameraId);
            Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (TextUtils.isDigitsOnly(limit)) {
                ISO = Long.parseLong(limit);
                if (ISO < isoRange.getLower()) {
                    ISO = isoRange.getLower();
                } else if (ISO > isoRange.getUpper()) {
                    ISO = isoRange.getUpper();
                }
            } else {
                Log.d(TAG, "Default ISO: " + isoRange);
                ISO = -1;
            }
            return ISO;
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void setIso(CaptureRequest.Builder captureBuilder, int iso) {
        try {
            CameraManager cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String mCameraId = null;
            String[] cameraIds = cameraManager.getCameraIdList();

            // Choose the first camera device
            if (cameraIds.length > 0) {
                mCameraId = cameraIds[0];
            } else {
                // No camera devices available
                // Handle the error accordingly
                showToast("No camera devices available");
                return;
            }

            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(mCameraId);
            Boolean isManualIsoSupported = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) != null
                    && characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES).length > 1;
            if (isManualIsoSupported) {
                // Set the ISO level
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            } else {
                // Manual ISO is not supported
                showToast("Manual ISO is not supported");
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
            showToast("CameraAccessException: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            showToast("IllegalArgumentException: " + e.getMessage());
        } catch (NullPointerException e) {
            e.printStackTrace();
            showToast("NullPointerException: " + e.getMessage());
        } catch (IllegalStateException e) {
            e.printStackTrace();
            showToast("IllegalStateException: " + e.getMessage());
        }
    }

        /**
     * Saves bitmap to jpeg format
     * @param bitmapImage source bitmap
     * @throws IOException when failed to create file
     */
    private void save(Bitmap bitmapImage, boolean result, long exposure, long iso) throws IOException {
        String flag = "";
        if (result) {
            flag = "ALARM";
        }
        String timeStamp = new SimpleDateFormat("yyyy-MM-dd(HH-mm-ss)", Locale.US).format(new Date());
        String exposureStr = String.valueOf(exposure/(SEC_TO_MILLI*SEC_TO_MILLI));
        String isoStr =String.valueOf(iso);
        String imageName = flag + "_green(" + greenValue(bitmapImage) +")"+  timeStamp +"exposure("+ exposureStr +")iso("+ isoStr +").jpg";
        String imageDescription = "greenValue image";
        String imagePath = MediaStore.Images.Media.insertImage(getContentResolver(), bitmapImage, imageName, imageDescription);
        showToast("Saved to:" + imagePath);
        if (imagePath != null) {
            Uri imageUri = Uri.parse(imagePath);
            curStorage += new File(getPathFromUri(imageUri)).length();

            addExifData(imageUri,exposure,iso);
        }
//        if (detectArea != null) {
//            File detectCrop = new File(
//                    Environment.getExternalStoragePublicDirectory(
//                            Environment.DIRECTORY_PICTURES
//                    ).getAbsolutePath() + "/" + flag + greenValue(bitmapImage) + "greenValue" +  timeStamp + "detectCrop.jpg");
//            try (OutputStream output = new FileOutputStream(detectCrop)) {
//                Bitmap detect = Bitmap.createBitmap(bitmapImage, detectArea.left, detectArea.top, detectArea.width(), detectArea.height());
//                detect.compress(Bitmap.CompressFormat.JPEG, 100, output);
//                curStorage += detectCrop.length();
//                output.flush();
//            }
//        }

        if (detectArea != null) {
            Bitmap detect = Bitmap.createBitmap(bitmapImage, detectArea.left, detectArea.top, detectArea.width(), detectArea.height());
            String d_imageName = flag + "_green(" + greenValue(bitmapImage) +")"+  timeStamp +"exposure("+ exposureStr +")iso("+ isoStr +").jpg";
            String d_imageDescription = "Cropped image";
            String d_imagePath = MediaStore.Images.Media.insertImage(getContentResolver(), detect, d_imageName, d_imageDescription);
            if (d_imagePath != null) {
                Uri imageUri = Uri.parse(d_imagePath);
                curStorage += new File(getPathFromUri(imageUri)).length();
            }
        }
    }

private void addExifData(Uri imageUri, long exposure, long iso) {
    try {
        String imagePath = getPathFromUri(imageUri);


        if (imagePath == null) {
            showToast("Image path is null");
            return;
        }

        File imageFile = new File(imagePath);

        if (!imageFile.exists()) {
            showToast("Image file does not exist");
            return;
        }

        ExifInterface exifInterface = new ExifInterface(imagePath);

        // Adding ISO & exposure time
        exifInterface.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, String.valueOf(iso));

        exifInterface.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, String.valueOf(exposure/(SEC_TO_MILLI*SEC_TO_MILLI)));

        // Save the changes to the image file
        exifInterface.saveAttributes();

        Log.d("EXIF", "EXIF data added successfully!");
    } catch (FileNotFoundException f) {
        Log.e("FileNotFound","Error searching for file"+f.getMessage());
    }
    catch (Exception e) {
        Log.e("EXIF", "Error adding EXIF data: " + e.getMessage());
    }
}
    public String getPathFromUri(Uri uri) {
        String[] projection = { MediaStore.Images.Media.DATA };
        Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
        assert cursor != null;
        int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
        cursor.moveToFirst();
        String filePath = cursor.getString(columnIndex);
        cursor.close();
        return filePath;
    }

    /**
     * Rotate bitmap clockwise
     * @param source source bitmap
     * @param angle angle to rotate clockwise
     * @return rotated bitmap
     */
    public Bitmap rotateBitmap(Bitmap source, float angle)
    {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    /**
     * Calculate the average of green components in the image
     * @param image source bitmap
     */
    private boolean analyzeImage(Bitmap image) {
        int green = 0;
        int blue = 0;
        int red = 0;
        int width = image.getWidth();
        int height = image.getHeight();
        if (detectArea == null) {
            for (int i = 0; i < image.getWidth(); i++) {
                for (int j = 0; j < image.getHeight(); j++) {
                    int color = image.getPixel(i, j);
                    green += Color.green(color);
                    blue += Color.blue(color);
                    red += Color.red(color);
                }
            }
        } else {
            width = detectArea.right - detectArea.left;
            height = detectArea.bottom - detectArea.top;
            for (int i = detectArea.left; i < detectArea.right; i++) {
                for (int j = detectArea.top; j < detectArea.bottom; j++) {
                    int color = image.getPixel(i, j);
                    green += Color.green(color);
                }
            }
        }
        int totalPix = width * height;
        green /= totalPix;
        red /= totalPix;
        blue /= totalPix;
        if (green > alarmThreshHold && red < 200 && blue < 200) {
            playAlarm();
            broadcastResult(true);
            return true;
        } else {
            broadcastResult(false);
            return false;
        }
    }

    //public int preenPixelValue()
    public String greenValue (Bitmap image){
        int green = 0;
        int blue = 0;
        int red = 0;
        int width = image.getWidth();
        int height = image.getHeight();
        if (detectArea == null) {
            for (int i = 0; i < image.getWidth(); i++) {
                for (int j = 0; j < image.getHeight(); j++) {
                    int color = image.getPixel(i, j);
                    green += Color.green(color);
                    blue += Color.blue(color);
                    red += Color.red(color);
                }
            }
        } else {
            width = detectArea.right - detectArea.left;
            height = detectArea.bottom - detectArea.top;
            for (int i = detectArea.left; i < detectArea.right; i++) {
                for (int j = detectArea.top; j < detectArea.bottom; j++) {
                    int color = image.getPixel(i, j);
                    green += Color.green(color);
                }
            }
        }
        int totalPix = width * height;
        green /= totalPix;
        return String.valueOf(green);
    }

    /**
     * Play an alarm for 5 seconds
     */
    private void playAlarm() {
        mp = MediaPlayer.create(mContext, Settings.System.DEFAULT_RINGTONE_URI);
        mp.start();
        showToast("Alarm launched!");
        mHandler.postDelayed(() -> mp.release(), 5 * SEC_TO_MILLI);
    }

    /**
     * Send broadcast with result. 0 for negative, 1 for positive
     * @param pos result of test, true for positive, false for negative
     */
    public void broadcastResult(boolean pos) {
        Intent intent = new Intent(TEST_RESULT);
        if (pos) {
            intent.putExtra("pos", 1);
        } else {
            intent.putExtra("pos", 0);
        }
        sendBroadcast(intent);
    }

    /**
     * Delete older photos until reaches the limit
     * @param limitB the limit in bytes
     * @return true if success
     */
    private boolean delete(long limitB) {
        File myDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES).getAbsolutePath() + "/");
        boolean result = false;
        if (myDir.isDirectory()) {
            String[] children = myDir.list();
            if (children != null) {
                int i = 0;
                while (curStorage > limitB) {
                    if (children[i] != null) {
                        File cur = new File(myDir, children[i]);
                        curStorage -= cur.length();
                        result = cur.delete();
                        showToast("Storage limit exceeded. Deleting older pictures.");
                        i++;
                    } else {
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Show toast message on the screen
     * @param msg text that will be shown
     */
    private void showToast(String msg) {
        if (mContext != null) {
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(() -> Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show());
        }
    }

    public Size getImageDimension() {
        return imageDimension;
    }

    public CameraDevice getCameraDevice() {
        return cameraDevice;
    }

    public void setDetectArea(Rect area) {
        this.detectArea = area;
    }

    /**
     * Calculates the storage size of the pictures folder
     * @return the size of the pictures folder
     */
    private long checkStorage() {
        long result = 0;

        Stack<File> dirList = new Stack<>();
        dirList.clear();

        dirList.push(new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES).getAbsolutePath() + "/"));

        while(!dirList.isEmpty()) {
            File dirCurrent = dirList.pop();

            File[] fileList = dirCurrent.listFiles();
            if (fileList == null) {
                result += 0;
            } else {
                for (File f : fileList) {
                    if (f.isDirectory())
                        dirList.push(f);
                    else
                        result += f.length();
                }
            }
        }
        return result;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "onTaskRemoved: called.");
        cameraDevice.close();
        if (mp != null) {
            mp.release();
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cameraDevice.close();
        if (mp != null) {
            mp.release();
        }
        Log.d(TAG, "onDestroy: called.");
    }
}

