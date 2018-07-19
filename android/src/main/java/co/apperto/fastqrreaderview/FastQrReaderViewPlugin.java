package co.apperto.fastqrreaderview;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetector;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetectorOptions;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.view.FlutterView;

/**
 * FastQrReaderViewPlugin
 */
public class FastQrReaderViewPlugin implements MethodCallHandler {

    private static final int CAMERA_REQUEST_ID = 513469796;
    private static final String TAG = "FastQrReaderViewPlugin";
    private static final SparseIntArray ORIENTATIONS =
            new SparseIntArray() {
                {
                    append(Surface.ROTATION_0, 0);
                    append(Surface.ROTATION_90, 90);
                    append(Surface.ROTATION_180, 180);
                    append(Surface.ROTATION_270, 270);
                }
            };

    private static CameraManager cameraManager;
    private final FlutterView view;
    private QrReader camera;
    private Activity activity;
    private Registrar registrar;
    // The code to run after requesting camera permissions.
    private Runnable cameraPermissionContinuation;
    private boolean requestingPermission;
    private static MethodChannel channel;


    private FastQrReaderViewPlugin(Registrar registrar, FlutterView view, Activity activity) {
        this.registrar = registrar;
        this.view = view;
        this.activity = activity;

        registrar.addRequestPermissionsResultListener(new CameraRequestPermissionsListener());

        activity
                .getApplication()
                .registerActivityLifecycleCallbacks(
                        new Application.ActivityLifecycleCallbacks() {
                            @Override
                            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                            }

                            @Override
                            public void onActivityStarted(Activity activity) {
                            }

                            @Override
                            public void onActivityResumed(Activity activity) {
                                if (requestingPermission) {
                                    requestingPermission = false;
                                    return;
                                }
                                if (activity == FastQrReaderViewPlugin.this.activity) {
                                    if (camera != null) {
                                        camera.open(null);
                                    }
                                }
                            }

                            @Override
                            public void onActivityPaused(Activity activity) {
                                if (activity == FastQrReaderViewPlugin.this.activity) {
                                    if (camera != null) {
                                        camera.close();
                                    }
                                }
                            }

                            @Override
                            public void onActivityStopped(Activity activity) {
                                if (activity == FastQrReaderViewPlugin.this.activity) {
                                    if (camera != null) {
                                        camera.close();
                                    }
                                }
                            }

                            @Override
                            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                            }

                            @Override
                            public void onActivityDestroyed(Activity activity) {
                            }
                        });
    }

    /**
     * Plugin registration.
     */
    public static void registerWith(Registrar registrar) {
        channel =
                new MethodChannel(registrar.messenger(), "fast_qr_reader_view");

        cameraManager = (CameraManager) registrar.activity().getSystemService(Context.CAMERA_SERVICE);

        channel.setMethodCallHandler(
                new FastQrReaderViewPlugin(registrar, registrar.view(), registrar.activity()));
    }


    @Override
    public void onMethodCall(MethodCall call, final Result result) {
        switch (call.method) {
            case "init":
                if (camera != null) {
                    camera.close();
                }
                result.success(null);
                break;
            case "availableCameras":
                try {
                    String[] cameraNames = cameraManager.getCameraIdList();
                    List<Map<String, Object>> cameras = new ArrayList<>();
                    for (String cameraName : cameraNames) {
                        HashMap<String, Object> details = new HashMap<>();
                        CameraCharacteristics characteristics =
                                cameraManager.getCameraCharacteristics(cameraName);
//                        Object test = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
//                        Log.d(TAG, "onMethodCall: "+test.toString());
                        details.put("name", cameraName);
                        @SuppressWarnings("ConstantConditions")
                        int lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                        switch (lensFacing) {
                            case CameraMetadata.LENS_FACING_FRONT:
                                details.put("lensFacing", "front");
                                break;
                            case CameraMetadata.LENS_FACING_BACK:
                                details.put("lensFacing", "back");
                                break;
                            case CameraMetadata.LENS_FACING_EXTERNAL:
                                details.put("lensFacing", "external");
                                break;
                        }
                        cameras.add(details);
                    }
                    result.success(cameras);
                } catch (CameraAccessException e) {
                    result.error("cameraAccess", e.getMessage(), null);
                }
                break;
            case "initialize": {
                String cameraName = call.argument("cameraName");
                String resolutionPreset = call.argument("resolutionPreset");
                if (camera != null) {
                    camera.close();
                }
                camera = new QrReader(cameraName, resolutionPreset, result);
                break;
            }
            case "startScanning":
                startScanning(result);
                break;
            case "stopScanning":
                stopScanning(result);
                break;
            case "dispose": {
                if (camera != null) {
                    camera.dispose();
                }
                result.success(null);
                break;
            }
            default:
                result.notImplemented();
                break;
        }
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow.
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private class CameraRequestPermissionsListener
            implements PluginRegistry.RequestPermissionsResultListener {
        @Override
        public boolean onRequestPermissionsResult(int id, String[] permissions, int[] grantResults) {
            if (id == CAMERA_REQUEST_ID) {
                cameraPermissionContinuation.run();
                return true;
            }
            return false;
        }
    }


    void startScanning(@NonNull Result result) {
        camera.scanning = true;
        result.success(null);
        camera.imageReader.setOnImageAvailableListener(
                new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader reader) {
                        if (camera.scanning) {
                            try (Image image = reader.acquireLatestImage()) {
                                FirebaseVisionImage test = FirebaseVisionImage.fromByteBuffer(image.getPlanes()[0].getBuffer(), new FirebaseVisionImageMetadata.Builder()
                                        .setFormat(FirebaseVisionImageMetadata.IMAGE_FORMAT_NV21)
                                        .setHeight(image.getHeight())
                                        .setWidth(image.getWidth())
                                        .build());
//                                FirebaseVisionImage test = FirebaseVisionImage.fromMediaImage(image, FirebaseVisionImageMetadata.ROTATION_0); // Slower in my experience
                                image.close();

                                camera.codeDetector.detectInImage(test).addOnSuccessListener(new OnSuccessListener<List<FirebaseVisionBarcode>>() {
                                    @Override
                                    public void onSuccess(List<FirebaseVisionBarcode> firebaseVisionBarcodes) {
                                        if (camera.scanning) {
                                            if (firebaseVisionBarcodes.size() > 0) {
                                                Log.w(TAG, "onSuccess: " + firebaseVisionBarcodes.get(0).getRawValue());
                                                channel.invokeMethod("updateCode", firebaseVisionBarcodes.get(0).getRawValue());
//                                                Map<String, String> event = new HashMap<>();
//                                                event.put("eventType", "cameraClosing");
//                                                camera.eventSink.success(event);
                                                stopScanning();
                                            }
                                        }
                                    }
                                }).addOnFailureListener(new OnFailureListener() {
                                    @Override
                                    public void onFailure(@NonNull Exception e) {
//                                            Log.d("test", "asdasd");
                                        e.printStackTrace();
                                    }
                                });
                            } catch (Exception e) {

                                e.printStackTrace();
                            }
                        }
                    }
                },
                camera.codeDetectionHandler);
        camera.imageReader.acquireLatestImage().close();
    }

    void stopScanning(@NonNull Result result) {
        stopScanning();
        result.success(null);
    }

    private void stopScanning() {
        camera.scanning = false;
        camera.imageReader.setOnImageAvailableListener(null, null);
//        camera.imageReader.close();
    }


    private class QrReader {

        private final FlutterView.SurfaceTextureEntry textureEntry;
        private CameraDevice cameraDevice;
        private CameraCaptureSession cameraCaptureSession;
        private EventChannel.EventSink eventSink;
        private ImageReader imageReader;
        private int sensorOrientation;
        private boolean isFrontFacing;
        private String cameraName;
        private Size captureSize;
        private Size previewSize;
        private CaptureRequest.Builder captureRequestBuilder;
        private Size videoSize;
        //        private MediaRecorder mediaRecorder;
//        private boolean recordingVideo;
        FirebaseVisionBarcodeDetectorOptions visionOptions;
        FirebaseVisionBarcodeDetector codeDetector;
        private Handler codeDetectionHandler = null;
        private HandlerThread mHandlerThread = null;

        private boolean scanning;

        QrReader(final String cameraName, final String resolutionPreset, @NonNull final Result result) {
            this.visionOptions = new FirebaseVisionBarcodeDetectorOptions.Builder()
                    .setBarcodeFormats(FirebaseVisionBarcode.FORMAT_QR_CODE)
                    .build();

            this.codeDetector = FirebaseVision.getInstance().getVisionBarcodeDetector(visionOptions);

            this.cameraName = cameraName;

            mHandlerThread = new HandlerThread("ImageDecoderThread");
            mHandlerThread.start();
            codeDetectionHandler = new Handler(mHandlerThread.getLooper());

            textureEntry = view.createSurfaceTexture();

            registerEventChannel();

            try {
                Size minPreviewSize;
                switch (resolutionPreset) {
                    case "high":
                        minPreviewSize = new Size(1024, 768);
                        break;
                    case "medium":
                        minPreviewSize = new Size(640, 480);
                        break;
                    case "low":
                        minPreviewSize = new Size(320, 240);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown preset: " + resolutionPreset);
                }

                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraName);
                StreamConfigurationMap streamConfigurationMap =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                //noinspection ConstantConditions
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                //noinspection ConstantConditions
                isFrontFacing =
                        characteristics.get(CameraCharacteristics.LENS_FACING)
                                == CameraMetadata.LENS_FACING_FRONT;
                computeBestCaptureSize(streamConfigurationMap);
                computeBestPreviewAndRecordingSize(streamConfigurationMap, minPreviewSize, captureSize);

                if (cameraPermissionContinuation != null) {
                    result.error("cameraPermission", "Camera permission request ongoing", null);
                }
                cameraPermissionContinuation =
                        new Runnable() {
                            @Override
                            public void run() {
                                cameraPermissionContinuation = null;
                                if (!hasCameraPermission()) {
                                    result.error(
                                            "cameraPermission", "MediaRecorderCamera permission not granted", null);
                                    return;
                                }
//                                if (!hasAudioPermission()) {
//                                    result.error(
//                                            "cameraPermission", "MediaRecorderAudio permission not granted", null);
//                                    return;
//                                }
                                open(result);
                            }
                        };
                requestingPermission = false;
                if (hasCameraPermission()) {
                    cameraPermissionContinuation.run();
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        requestingPermission = true;
                        registrar
                                .activity()
                                .requestPermissions(
                                        new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                                        CAMERA_REQUEST_ID);
                    }
                }
            } catch (CameraAccessException e) {
                result.error("CameraAccess", e.getMessage(), null);
            } catch (IllegalArgumentException e) {
                result.error("IllegalArgumentException", e.getMessage(), null);
            }
        }

        private void registerEventChannel() {
            new EventChannel(
                    registrar.messenger(), "fast_qr_reader_view/cameraEvents" + textureEntry.id())
                    .setStreamHandler(
                            new EventChannel.StreamHandler() {
                                @Override
                                public void onListen(Object arguments, EventChannel.EventSink eventSink) {
                                    QrReader.this.eventSink = eventSink;
                                }

                                @Override
                                public void onCancel(Object arguments) {
                                    QrReader.this.eventSink = null;
                                }
                            });
        }

        private boolean hasCameraPermission() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || activity.checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
        }

//        private boolean hasAudioPermission() {
//            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
//                    || registrar.activity().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
//                    == PackageManager.PERMISSION_GRANTED;
//        }

        private void computeBestPreviewAndRecordingSize(
                StreamConfigurationMap streamConfigurationMap, Size minPreviewSize, Size captureSize) {
            Size[] sizes = streamConfigurationMap.getOutputSizes(SurfaceTexture.class);
            float captureSizeRatio = (float) captureSize.getWidth() / captureSize.getHeight();
            List<Size> goodEnough = new ArrayList<>();
            for (Size s : sizes) {
                if ((float) s.getWidth() / s.getHeight() == captureSizeRatio
                        && minPreviewSize.getWidth() < s.getWidth()
                        && minPreviewSize.getHeight() < s.getHeight()) {
                    goodEnough.add(s);
                }
            }

            Collections.sort(goodEnough, new CompareSizesByArea());

            if (goodEnough.isEmpty()) {
                previewSize = sizes[0];
                videoSize = sizes[0];
            } else {
                previewSize = goodEnough.get(0);

                // Video capture size should not be greater than 1080 because MediaRecorder cannot handle higher resolutions.
                videoSize = goodEnough.get(0);
                for (int i = goodEnough.size() - 1; i >= 0; i--) {
                    if (goodEnough.get(i).getHeight() <= 1080) {
                        videoSize = goodEnough.get(i);
                        break;
                    }
                }
            }
        }

        private void computeBestCaptureSize(StreamConfigurationMap streamConfigurationMap) {
            // For still image captures, we use the largest available size.
            captureSize =
                    Collections.max(
                            Arrays.asList(streamConfigurationMap.getOutputSizes(ImageFormat.YUV_420_888)),
                            new CompareSizesByArea());
        }


        @SuppressLint("MissingPermission")
        private void open(@Nullable final Result result) {
            if (!hasCameraPermission()) {
                if (result != null)
                    result.error("cameraPermission", "Camera permission not granted", null);
            } else {
                try {
                    imageReader =
                            ImageReader.newInstance(
                                    captureSize.getWidth(), captureSize.getHeight(), ImageFormat.YUV_420_888, 2);
                    cameraManager.openCamera(
                            cameraName,
                            new CameraDevice.StateCallback() {
                                @Override
                                public void onOpened(@NonNull CameraDevice cameraDevice) {
                                    QrReader.this.cameraDevice = cameraDevice;
                                    try {
                                        startPreview();
                                    } catch (CameraAccessException e) {
                                        if (result != null)
                                            result.error("CameraAccess", e.getMessage(), null);
                                    }

                                    if (result != null) {
                                        Map<String, Object> reply = new HashMap<>();
                                        reply.put("textureId", textureEntry.id());
                                        reply.put("previewWidth", previewSize.getWidth());
                                        reply.put("previewHeight", previewSize.getHeight());
                                        result.success(reply);
                                    }
                                }

                                @Override
                                public void onClosed(@NonNull CameraDevice camera) {
                                    if (eventSink != null) {
                                        Map<String, String> event = new HashMap<>();
                                        event.put("eventType", "cameraClosing");
                                        eventSink.success(event);
                                    }
                                    super.onClosed(camera);
                                }

                                @Override
                                public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                                    cameraDevice.close();
                                    QrReader.this.cameraDevice = null;
                                    sendErrorEvent("The camera was disconnected.");
                                }

                                @Override
                                public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
                                    cameraDevice.close();
                                    QrReader.this.cameraDevice = null;
                                    String errorDescription;
                                    switch (errorCode) {
                                        case ERROR_CAMERA_IN_USE:
                                            errorDescription = "The camera device is in use already.";
                                            break;
                                        case ERROR_MAX_CAMERAS_IN_USE:
                                            errorDescription = "Max cameras in use";
                                            break;
                                        case ERROR_CAMERA_DISABLED:
                                            errorDescription =
                                                    "The camera device could not be opened due to a device policy.";
                                            break;
                                        case ERROR_CAMERA_DEVICE:
                                            errorDescription = "The camera device has encountered a fatal error";
                                            break;
                                        case ERROR_CAMERA_SERVICE:
                                            errorDescription = "The camera service has encountered a fatal error.";
                                            break;
                                        default:
                                            errorDescription = "Unknown camera error";
                                    }
                                    sendErrorEvent(errorDescription);
                                }
                            },
                            null);
                } catch (CameraAccessException e) {
                    if (result != null) result.error("cameraAccess", e.getMessage(), null);
                }
            }
        }


        private void startPreview() throws CameraAccessException {


//            FirebaseVisionBarcodeDetectorOptions options = new FirebaseVisionBarcodeDetectorOptions.Builder()
//                    .setBarcodeFormats(FirebaseVisionBarcode.FORMAT_QR_CODE)
//                    .build();
//
//            FirebaseVisionBarcodeDetector detector = FirebaseVision.getInstance()
//                    .getVisionBarcodeDetector(options);
////detector.detectInImage(FirebaseVisionImage.)
////        detector.detectInImage(FirebaseVisionImage.fromByteBuffer())
//            closeCaptureSession();

            SurfaceTexture surfaceTexture = textureEntry.surfaceTexture();
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            List<Surface> surfaces = new ArrayList<>();


            Surface previewSurface = new Surface(surfaceTexture);

            surfaces.add(previewSurface);
            surfaces.add(imageReader.getSurface());
            captureRequestBuilder.addTarget(previewSurface);
            captureRequestBuilder.addTarget(imageReader.getSurface());


            camera.
                    cameraDevice.createCaptureSession(
                    surfaces,
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) {
                                sendErrorEvent("The camera was closed during configuration.");
                                return;
                            }
                            try {
                                cameraCaptureSession = session;
                                captureRequestBuilder.set(
                                        CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                                    @Override
                                    public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                                        super.onCaptureCompleted(session, request, result);
                                    }
                                }, null);
                            } catch (CameraAccessException e) {
                                sendErrorEvent(e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            sendErrorEvent("Failed to configure the camera for preview.");
                        }
                    },
                    null);
        }

        private void sendErrorEvent(String errorDescription) {
            if (eventSink != null) {
                Map<String, String> event = new HashMap<>();
                event.put("eventType", "error");
                event.put("errorDescription", errorDescription);
                eventSink.success(event);
            }
        }

        private void closeCaptureSession() {
            if (cameraCaptureSession != null) {
                cameraCaptureSession.close();
                cameraCaptureSession = null;
            }
        }

        private void close() {
            closeCaptureSession();

            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        }

        private void dispose() {
            close();
            textureEntry.release();
        }
    }
}
