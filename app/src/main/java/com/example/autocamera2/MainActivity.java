package com.example.autocamera2;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.Collections;

/**
 * Main activity of the app
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "mainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    public static final int SECOND_TO_MILLI = 1000;
    public static boolean frontBack;
    private TakePhotoService mService;
    boolean mBounded;
    private AutoFitTextureView textureView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSessions;
    private CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension;
    private DragRectView drag;
    private int dx;
    private int dy;

    /**
     * Sets up the button click listeners and starts the service
     * @param savedInstanceState instance state
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        setContentView(R.layout.activity_main);
        EditText editText = findViewById(R.id.editTextNumber);
        EditText editText2 = findViewById(R.id.editTextNumber2);
        ToggleButton toggleBtn = findViewById(R.id.toggleButton);
        ToggleButton cam = findViewById(R.id.switchCam);
        textureView = findViewById(R.id.textureView);
        Button done = findViewById(R.id.done);
        registerReceiver(myReceiver, new IntentFilter(TakePhotoService.INTENT_FILTER));

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_CAMERA_PERMISSION);
        }
        //bind to service
        Intent mIntent = new Intent(MainActivity.this, TakePhotoService.class);
        bindService(mIntent, mConnection, BIND_AUTO_CREATE);

        toggleBtn.setOnClickListener(view -> {
            boolean on = ((ToggleButton) view).isChecked();
            if (!on) {
                mService.pauseTask();
                createCameraPreview();
            } else {
                int interval = Integer.parseInt(String.valueOf(editText.getText())) * SECOND_TO_MILLI;
                int alarmThreshHold = Integer.parseInt(String.valueOf(editText2.getText()));
                mService.setInterval(interval);
                mService.setAlarmThreshHold(alarmThreshHold);
                mService.unPauseTask();
            }
        });

        cam.setOnClickListener(view -> {
            boolean on = ((ToggleButton) view).isChecked();
            mService.onDestroy();
            if (on) {
                frontBack = true;
            } else {
                frontBack = false;
            }
            mService.openCamera();
        });

        textureView.setSurfaceTextureListener(textureListener);
        textureView.setOnClickListener(view -> mService.takePicture());

        drag = findViewById(R.id.drag);

        done.setOnClickListener(view -> {
            drag.setVisibility(View.GONE);
            done.setVisibility(View.GONE);
        });

        if (null != drag) {
            drag.setOnUpCallback(rect -> {
                if (rect != null) {
                    Toast.makeText(getApplicationContext(), "Selected area is (" + rect.left + ", " + rect.top + ", " + rect.right + ", " + rect.bottom + ")",
                            Toast.LENGTH_LONG).show();
                    rect.offset(dx, dy);
                }
                mService.setDetectArea(rect);
            });
        }

        dx = 0;
        dy = 0;
    }

    /**
     * Sets up button click listeners for menu item
     * @param menu menu
     * @return return true
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.my_menu, menu);
        MenuItem exposure = menu.findItem(R.id.exposure);
        exposure.setOnMenuItemClickListener(view -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
            return true;
        });

        MenuItem editArea = menu.findItem(R.id.detect);
        editArea.setOnMenuItemClickListener(view -> {
            DragRectView dragRectView = findViewById(R.id.drag);
            Button done = findViewById(R.id.done);
            if (dragRectView.getVisibility() == View.GONE) {
                dragRectView.setVisibility(View.VISIBLE);
                done.setVisibility(View.VISIBLE);
            } else {
                dragRectView.setVisibility(View.GONE);
                done.setVisibility(View.GONE);
            }
            return true;
        });
        MenuItem reportP = menu.findItem(R.id.report_positive);
        reportP.setOnMenuItemClickListener(view -> {
            mService.broadcastResult(true);
            return true;
        });
        MenuItem reportN = menu.findItem(R.id.report_negative);
        reportN.setOnMenuItemClickListener(view -> {
            mService.broadcastResult(false);
            return true;
        });
        return super.onCreateOptionsMenu(menu);
    }

    /**
     * Sets the rotation and size for preview
     * @param viewWidth width of preview
     * @param viewHeight height of preview
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        //int rotation = mService.getmSensorOrientation() / 90;
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, imageDimension.getHeight(), imageDimension.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        dx = (int) (centerX - bufferRect.centerX()) > 0 ? 0 : Math.abs((int) (centerX - bufferRect.centerX()));
        dy = (int) (centerY - bufferRect.centerY()) > 0 ? 0 : Math.abs((int) (centerY - bufferRect.centerY()));
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);

        RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams)
                drag.getLayoutParams();
        layoutParams.width = (int) Math.min(bufferRect.right - bufferRect.left, viewRect.right - viewRect.left);
        layoutParams.height = (int) Math.min(bufferRect.bottom - bufferRect.top, viewRect.bottom - viewRect.top);
        drag.setLayoutParams(layoutParams);
        textureView.setTransform(matrix);
    }

    /**
     * Create camera preview when service opened camera
     */
    private final BroadcastReceiver myReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            createCameraPreview();
        }
    };

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    /**
     * Create capture session for preview
     */
    protected void createCameraPreview() {
        try {
            cameraDevice = mService.getCameraDevice();
            imageDimension = mService.getImageDimension();

            configureTransform(textureView.getWidth(), textureView.getHeight());

            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (null == cameraDevice) {
                        return;
                    }
                    // When the session is ready, we start displaying the preview.
                    cameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Start showing preview if config succeed
     */
    protected void updatePreview() {
        if (null == cameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        try {

            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Handles permission result for camera and storage
     * @param requestCode requestCode
     * @param permissions permissions
     * @param grantResults grantResults
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(MainActivity.this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    /**
     * Connection for service
     */
    ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Toast.makeText(MainActivity.this, "Service is disconnected", Toast.LENGTH_SHORT).show();
            mBounded = false;
            mService = null;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Toast.makeText(MainActivity.this, "Service is connected", Toast.LENGTH_SHORT).show();
            mBounded = true;
            TakePhotoService.MyBinder mLocalBinder = (TakePhotoService.MyBinder) service;
            mService = mLocalBinder.getService();
            mService.openCamera();
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(myReceiver);
    }
}
