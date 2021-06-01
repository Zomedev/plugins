package io.flutter.plugins.camera;

import static android.view.OrientationEventListener.ORIENTATION_UNKNOWN;
import static io.flutter.plugins.camera.CameraUtils.computeBestPreviewSize;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import androidx.annotation.NonNull;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugins.camera.barcodes.BarcodeScanner;
import io.flutter.plugins.camera.media.MediaRecorderBuilder;
import io.flutter.view.TextureRegistry.SurfaceTextureEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import android.util.Log;

public class Camera {
  private final SurfaceTextureEntry flutterTexture;
  private final CameraManager cameraManager;
  private final OrientationEventListener orientationEventListener;
  private final boolean isFrontFacing;
  private final int sensorOrientation;
  private final boolean hasFlashSupport;
  private final String cameraName;
  private final Size captureSize;
  private final Size previewSize;
  private final boolean enableAudio;

  // Sizes based more closely on https://developer.android.com/reference/android/hardware/camera2/CameraDevice#createCaptureSession(android.hardware.camera2.params.SessionConfiguration)
  // Note we can have up to 3 targets
  private final Size flutterSurfaceSize;
  private final Size pictureReaderSize;
  private final Size barcodeReaderSize;

  private CameraDevice cameraDevice;
  private CameraCaptureSession cameraCaptureSession;
  private ImageReader pictureImageReader;
  private ImageReader imageStreamReader;
  private ImageReader barcodeScanningReader;
  private BarcodeScanner barcodeScanner;
  private DartMessenger dartMessenger;
  private CaptureRequest.Builder captureRequestBuilder;
  private MediaRecorder mediaRecorder;
  private boolean recordingVideo;
  private CamcorderProfile recordingProfile;
  private int currentOrientation = ORIENTATION_UNKNOWN;

  // Mirrors camera.dart
  public enum ResolutionPreset {
    low,
    medium,
    high,
    veryHigh,
    ultraHigh,
    max,
  }

  public Camera(
      final Activity activity,
      final SurfaceTextureEntry flutterTexture,
      final DartMessenger dartMessenger,
      final String cameraName,
      final String resolutionPreset,
      final boolean enableAudio)
      throws CameraAccessException {
    if (activity == null) {
      throw new IllegalStateException("No activity available!");
    }
    this.cameraName = cameraName;
    this.enableAudio = enableAudio;
    this.flutterTexture = flutterTexture;
    this.dartMessenger = dartMessenger;
    this.cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    orientationEventListener =
        new OrientationEventListener(activity.getApplicationContext()) {
          @Override
          public void onOrientationChanged(int i) {
            if (i == ORIENTATION_UNKNOWN) {
              return;
            }
            // Convert the raw deg angle to the nearest multiple of 90.
            currentOrientation = (int) Math.round(i / 90.0) * 90;
          }
        };
    orientationEventListener.enable();

    ResolutionPreset preset = ResolutionPreset.valueOf(resolutionPreset);

    int cameraHardwareLevel = CameraUtils.getHardwareLevel(activity, cameraName);
    Size cameraPreviewSize = CameraUtils.getPreviewSize(activity, cameraName, preset);
    Size cameraRecordSize = CameraUtils.getRecordSize(cameraName, preset);
    Size cameraMaximumSize = CameraUtils.getMaximumSize(activity, cameraName, ImageFormat.JPEG);
    switch(cameraHardwareLevel) {
      case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
      case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
      case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
      case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
      default:
        // We effective use PRIV, YUV, JPEG, so legacy matched
        flutterSurfaceSize = cameraPreviewSize;
        barcodeReaderSize = cameraRecordSize;
        pictureReaderSize = cameraRecordSize; // Could be maximum, clamped by resolution...
        break;
    }

    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraName);
    StreamConfigurationMap streamConfigurationMap =
        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    //noinspection ConstantConditions
    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
    //noinspection ConstantConditions
    isFrontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT;
    hasFlashSupport = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != null;
    recordingProfile = CameraUtils.getBestAvailableCamcorderProfileForResolutionPreset(cameraName, preset);
    captureSize = new Size(recordingProfile.videoFrameWidth, recordingProfile.videoFrameHeight);
    previewSize = computeBestPreviewSize(cameraName, preset);

    barcodeScanner = new BarcodeScanner(activity);
  }

  private void prepareMediaRecorder(String outputFilePath) throws IOException {
    if (mediaRecorder != null) {
      mediaRecorder.release();
    }

    mediaRecorder =
        new MediaRecorderBuilder(recordingProfile, outputFilePath)
            .setEnableAudio(enableAudio)
            .setMediaOrientation(getMediaOrientation())
            .build();
  }

  @SuppressLint("MissingPermission")
  public void open(@NonNull final Result result) throws CameraAccessException {
    pictureImageReader =
        ImageReader.newInstance(
            pictureReaderSize.getWidth(), pictureReaderSize.getHeight(), ImageFormat.JPEG, 2);

    // Used to steam image byte data to dart side.
    imageStreamReader =
        ImageReader.newInstance(
            barcodeReaderSize.getWidth(), barcodeReaderSize.getHeight(), ImageFormat.YUV_420_888, 2);

    // Use to scan for barcodes
    barcodeScanningReader =
       ImageReader.newInstance(
           barcodeReaderSize.getWidth(), barcodeReaderSize.getHeight(), ImageFormat.YUV_420_888, 2);

    cameraManager.openCamera(
        cameraName,
        new CameraDevice.StateCallback() {
          @Override
          public void onOpened(@NonNull CameraDevice device) {
            cameraDevice = device;
            try {
              Map<String, Object> reply = new HashMap<>();
              reply.put("textureId", flutterTexture.id());
              reply.put("previewWidth", flutterSurfaceSize.getWidth());
              reply.put("previewHeight", flutterSurfaceSize.getHeight());
              reply.put("hasFlash", hasFlashSupport);

              startPreview(result, reply);
            } catch (CameraAccessException e) {
              result.error("CameraAccess", e.getMessage(), null);
              close();
              return;
            }
          }

          @Override
          public void onClosed(@NonNull CameraDevice camera) {
            dartMessenger.sendCameraClosingEvent();
            super.onClosed(camera);
          }

          @Override
          public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            close();
            dartMessenger.send(DartMessenger.EventType.ERROR, "The camera was disconnected.");
          }

          @Override
          public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
            close();
            String errorDescription;
            switch (errorCode) {
              case ERROR_CAMERA_IN_USE:
                errorDescription = "The camera device is in use already.";
                break;
              case ERROR_MAX_CAMERAS_IN_USE:
                errorDescription = "Max cameras in use";
                break;
              case ERROR_CAMERA_DISABLED:
                errorDescription = "The camera device could not be opened due to a device policy.";
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
            dartMessenger.send(DartMessenger.EventType.ERROR, errorDescription);
          }
        },
        null);
  }

  private void writeToFile(ByteBuffer buffer, File file) throws IOException {
    try (FileOutputStream outputStream = new FileOutputStream(file)) {
      while (0 < buffer.remaining()) {
        outputStream.getChannel().write(buffer);
      }
    }
  }

  SurfaceTextureEntry getFlutterTexture() {
    return flutterTexture;
  }

  public void takePicture(String filePath, boolean useFlash, @NonNull final Result result) {
    final File file = new File(filePath);

    if (file.exists()) {
      result.error(
          "fileExists", "File at path '" + filePath + "' already exists. Cannot overwrite.", null);
      return;
    }

    pictureImageReader.setOnImageAvailableListener(
        reader -> {
          try (Image image = reader.acquireLatestImage()) {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            writeToFile(buffer, file);
            result.success(null);
          } catch (IOException e) {
            result.error("IOError", "Failed saving image", null);
          }
        },
        null);

    try {
      final CaptureRequest.Builder captureBuilder =
          cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
      captureBuilder.addTarget(pictureImageReader.getSurface());
      captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getMediaOrientation());

      if(hasFlashSupport && useFlash) {
        captureBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE);
      }

      cameraCaptureSession.capture(
          captureBuilder.build(),
          new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureFailed(
                @NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request,
                @NonNull CaptureFailure failure) {
              String reason;
              switch (failure.getReason()) {
                case CaptureFailure.REASON_ERROR:
                  reason = "An error happened in the framework";
                  break;
                case CaptureFailure.REASON_FLUSHED:
                  reason = "The capture has failed due to an abortCaptures() call";
                  break;
                default:
                  reason = "Unknown reason";
              }
              result.error("captureFailure", reason, null);
            }
          },
          null);
    } catch (CameraAccessException e) {
      result.error("cameraAccess", e.getMessage(), null);
    }
  }

  private void createCaptureSession(final Result result, final Map<String, Object> resultSuccess, int templateType, Surface targetSurface, Surface otherSurface)
      throws CameraAccessException {
    createCaptureSession(result, resultSuccess, templateType, null, targetSurface, otherSurface);
  }

  private void resultError(final Result result, final String tag, final String msg) {
    new Handler(Looper.getMainLooper()).post(new Runnable() {
      @Override
      public void run() {
        result.error(tag, msg, null);
      }
    });
  }

  private void resultSuccess(final Result result, final Map<String, Object> response) {
    new Handler(Looper.getMainLooper()).post(new Runnable() {
      @Override
      public void run() {
        result.success(response);
      }
    });
  }

  private void createCaptureSession(
      final Result result, final Map<String, Object> resultSuccess,
      int templateType, Runnable onSuccessCallback, Surface targetSurface, Surface otherSurface)
      throws CameraAccessException {
    // Close any existing capture session.
    closeCaptureSession();

    // Create a new capture builder.
    captureRequestBuilder = cameraDevice.createCaptureRequest(templateType);

    // Build Flutter surface to render to
    SurfaceTexture surfaceTexture = flutterTexture.surfaceTexture();
    surfaceTexture.setDefaultBufferSize(flutterSurfaceSize.getWidth(), flutterSurfaceSize.getHeight());
    Surface flutterSurface = new Surface(surfaceTexture);
    captureRequestBuilder.addTarget(flutterSurface);

    if(targetSurface != null)
      captureRequestBuilder.addTarget(targetSurface);

    // Prepare the callback
    CameraCaptureSession.StateCallback callback =
        new CameraCaptureSession.StateCallback() {
          @Override
          public void onConfigured(@NonNull CameraCaptureSession session) {
            try {
              if (cameraDevice == null) {
                dartMessenger.send(
                    DartMessenger.EventType.ERROR, "The camera was closed during configuration.");

                if(result != null) {
                  resultError(result,"createCaptureSession", "no camera device");
                  return;
                }
              }
              cameraCaptureSession = session;
              captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

              cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
              if (onSuccessCallback != null) {
                onSuccessCallback.run();
              }

              if(result != null) {
                resultSuccess(result, resultSuccess);
                return;
              }
            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
              dartMessenger.send(DartMessenger.EventType.ERROR, e.getMessage());

              if(result != null) {
                resultError(result, "createCaptureSession", e.getMessage());
                return;
              }
            }
          }

          @Override
          public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            dartMessenger.send(
                DartMessenger.EventType.ERROR, "Failed to configure camera session.");
          }
        };

    // Start the session
    if (VERSION.SDK_INT >= VERSION_CODES.P) {
      // Collect all surfaces we want to render to.
      List<OutputConfiguration> configs = new ArrayList<>();
      configs.add(new OutputConfiguration(flutterSurface));
      if(targetSurface != null)
        configs.add(new OutputConfiguration(targetSurface));
      if(otherSurface != null)
        configs.add(new OutputConfiguration(otherSurface));

      createCaptureSessionWithSessionConfig(configs, callback);
    } else {
      // Collect all surfaces we want to render to.
      List<Surface> surfaceList = new ArrayList<>();
      surfaceList.add(flutterSurface);
      if(targetSurface != null)
        surfaceList.add(targetSurface);
      if(otherSurface != null)
        surfaceList.add(otherSurface);
      createCaptureSession(surfaceList, callback);
    }
  }

  @TargetApi(VERSION_CODES.P)
  private void createCaptureSessionWithSessionConfig(
      List<OutputConfiguration> outputConfigs, CameraCaptureSession.StateCallback callback)
      throws CameraAccessException {
    cameraDevice.createCaptureSession(
        new SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigs,
            Executors.newSingleThreadExecutor(),
            callback));
  }

  @TargetApi(VERSION_CODES.LOLLIPOP)
  @SuppressWarnings("deprecation")
  private void createCaptureSession(
      List<Surface> surfaces, CameraCaptureSession.StateCallback callback)
      throws CameraAccessException {
    cameraDevice.createCaptureSession(surfaces, callback, null);
  }

  public void startVideoRecording(String filePath, Result result) {
    if (new File(filePath).exists()) {
      result.error("fileExists", "File at path '" + filePath + "' already exists.", null);
      return;
    }
    try {
      prepareMediaRecorder(filePath);
      recordingVideo = true;
      createCaptureSession(result, null,
          CameraDevice.TEMPLATE_RECORD, () -> mediaRecorder.start(), mediaRecorder.getSurface(), null);
      result.success(null);
    } catch (CameraAccessException | IOException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
    }
  }

  public void stopVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      recordingVideo = false;
      mediaRecorder.stop();
      mediaRecorder.reset();
      startPreview(result, null);
    } catch (CameraAccessException | IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
    }
  }

  public void pauseVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder.pause();
      } else {
        result.error("videoRecordingFailed", "pauseVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }

  public void resumeVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder.resume();
      } else {
        result.error(
            "videoRecordingFailed", "resumeVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }

  public void startPreview(final Result result, final Map<String, Object> resultSuccess) throws CameraAccessException {
    barcodeScanner.stop();
    if (pictureImageReader == null || pictureImageReader.getSurface() == null) return;

    createCaptureSession(result, resultSuccess, CameraDevice.TEMPLATE_PREVIEW, null, pictureImageReader.getSurface());
  }

  public void startPreviewWithImageStream(EventChannel imageStreamChannel, final Result result)
      throws CameraAccessException {
    if(imageStreamReader == null) {
      result.error("imageStreamReader null", "imageStreamReader null in startPreviewWithImageStream likely because camera is closed", null);
      return;
    }

    createCaptureSession(result, null, CameraDevice.TEMPLATE_RECORD, imageStreamReader.getSurface(), null);

    imageStreamChannel.setStreamHandler(
        new EventChannel.StreamHandler() {
          @Override
          public void onListen(Object o, EventChannel.EventSink imageStreamSink) {
            setImageStreamImageAvailableListener(imageStreamSink);
          }

          @Override
          public void onCancel(Object o) {
            if(imageStreamReader != null)
              imageStreamReader.setOnImageAvailableListener(null, null);
          }
        });
  }

  private void setImageStreamImageAvailableListener(final EventChannel.EventSink imageStreamSink) {
    if(imageStreamReader == null) {
      imageStreamSink.error("imageStreamReader null", "imageStreamReader null on setImageStreamImageAvailableListener likely due to rapid camera.open/close", null);
      return;
    }

    imageStreamReader.setOnImageAvailableListener(
        reader -> {
          Image img = reader.acquireLatestImage();
          if (img == null) return;

          List<Map<String, Object>> planes = new ArrayList<>();
          for (Image.Plane plane : img.getPlanes()) {
            ByteBuffer buffer = plane.getBuffer();

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes, 0, bytes.length);

            Map<String, Object> planeBuffer = new HashMap<>();
            planeBuffer.put("bytesPerRow", plane.getRowStride());
            planeBuffer.put("bytesPerPixel", plane.getPixelStride());
            planeBuffer.put("bytes", bytes);

            planes.add(planeBuffer);
          }

          Map<String, Object> imageBuffer = new HashMap<>();
          imageBuffer.put("width", img.getWidth());
          imageBuffer.put("height", img.getHeight());
          imageBuffer.put("format", img.getFormat());
          imageBuffer.put("planes", planes);

          imageStreamSink.success(imageBuffer);
          img.close();
        },
        null);
  }

  public void startPreviewWithBarcodeScanning(EventChannel barcodeScannerChannel, final Result result)
     throws CameraAccessException {

    if(barcodeScanningReader == null) {
      result.error("barcodeScanningReader null", "barcodeScanningReader null in startPreviewWithBarcodeScanning likely because camera is closed", null);
      return;
    }

    createCaptureSession(result, null, CameraDevice.TEMPLATE_PREVIEW, barcodeScanningReader.getSurface(), pictureImageReader.getSurface());

    barcodeScanner.start();
    barcodeScannerChannel.setStreamHandler(
       new EventChannel.StreamHandler() {
         @Override
         public void onListen(Object o, EventChannel.EventSink barcodeScanningSink) {
           setBarcodeScanningImageAvailableListener(barcodeScanningSink);
         }

         @Override
         public void onCancel(Object o) {
           if(barcodeScanningReader != null)
             barcodeScanningReader.setOnImageAvailableListener(null, null);
         }
       });
  }

  public void pausePreviewWithBarcodeScanning() {
    barcodeScanner.pause();
  }

  public void resumePreviewWithBarcodeScanning() {
    barcodeScanner.resume();
  }

  private void setBarcodeScanningImageAvailableListener(final EventChannel.EventSink barcodeScanningSink) {
    if(barcodeScanningReader == null) {
      barcodeScanningSink.error("barcodeScanningReader null", "barcodeScanningReader null on setBarcodeScanningImageAvailableListener likely due to rapid camera.open/close", null);
      return;
    }

    barcodeScanner.setSink(barcodeScanningSink);
    barcodeScanningReader.setOnImageAvailableListener(
       reader -> {
         Image img = reader.acquireLatestImage();
         if (img == null) return;

         barcodeScanner.submitImage(img, getMediaOrientation());

         img.close();
       },
       null);
  }

  private void closeCaptureSession() {
    if (cameraCaptureSession != null) {
      cameraCaptureSession.close();
      cameraCaptureSession = null;
    }
  }

  public void close() {
    closeCaptureSession();

    if (cameraDevice != null) {
      cameraDevice.close();
      cameraDevice = null;
    }
    if (pictureImageReader != null) {
      pictureImageReader.close();
      pictureImageReader = null;
    }
    if (imageStreamReader != null) {
      imageStreamReader.close();
      imageStreamReader = null;
    }
    if (barcodeScanningReader != null) {
      barcodeScanningReader.close();
      barcodeScanningReader = null;
    }

    if( barcodeScanner != null) {
      barcodeScanner.stop();
    }

    if (mediaRecorder != null) {
      mediaRecorder.reset();
      mediaRecorder.release();
      mediaRecorder = null;
    }
  }

  public void dispose() {
    close();
    flutterTexture.release();
    orientationEventListener.disable();
  }

  private int getMediaOrientation() {
    final int sensorOrientationOffset =
        (currentOrientation == ORIENTATION_UNKNOWN)
            ? 0
            : (isFrontFacing) ? -currentOrientation : currentOrientation;
    return (sensorOrientationOffset + sensorOrientation + 360) % 360;
  }
}
