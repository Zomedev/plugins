package io.flutter.plugins.camera.barcodes;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.Surface;
import android.view.WindowManager;

import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;

import io.flutter.plugin.common.EventChannel;

import java.nio.ByteBuffer;

public class BarcodeScanner {
  enum State {
    Stopped,
    Running,
    Paused
  }

  private final Object stateLock = new Object();
  private State state = State.Stopped;
  private State requestedState = null;
  private Thread workerThread = null;

  private boolean pending = false;
  private long pendingTimestamp;
  private BarcodeImage pendingImage = new BarcodeImage();
  private BarcodeImage processingImage = new BarcodeImage();
  private BarcodeImage scaledImage = new BarcodeImage();

  private BarcodeDetector detector;
  private BarcodeTrackerFactory tracker;

  private int frameId = 0;
  private int displayRotation = 0;

  public BarcodeScanner(Context activityContext) {
    detector = new BarcodeDetector.Builder(activityContext.getApplicationContext())
       .setBarcodeFormats(Barcode.QR_CODE)
       .build();

    tracker = new BarcodeTrackerFactory();
    detector.setProcessor(new MultiProcessor.Builder<>(tracker).build());
  }

  public void start() {
    synchronized(stateLock) {
      if(state != State.Stopped)
        return;

      requestedState = State.Running;

      workerThread = new Thread(new Worker());
      workerThread.start();

      waitForStateChange(State.Running);
    }
  }

  public void stop() {
    synchronized (stateLock) {
      if(state == State.Stopped)
        return;

      requestedState = State.Stopped;
      waitForStateChange(State.Stopped);

      detector.release();

      try {
        workerThread.join();
      } catch (InterruptedException ie) {
      } finally {
        workerThread = null;
      }
    }
  }

  public void pause() {
    synchronized (stateLock) {
      if(state != State.Running)
        return;

      requestedState = State.Paused;
      waitForStateChange(State.Paused);
    }
  }

  public void resume() {
    synchronized (stateLock) {
      if(state != state.Paused)
        return;

      requestedState = State.Running;
      waitForStateChange(State.Running);
    }
  }

  private void waitForStateChange(State newState) {
    stateLock.notifyAll();
    while(state != newState) {
      try {
        stateLock.wait();
      } catch (InterruptedException ie) {
        state = State.Stopped;
        stateLock.notifyAll();
        workerThread = null;
      }
    }
  }

  public void setSink(EventChannel.EventSink sink) {
    tracker.setSink(sink);
  }

  public void submitImage(Image image, int cameraOrientation) {
    int frameRotation;
    switch(cameraOrientation) {
      case 0: frameRotation = Frame.ROTATION_0; break;
      case 90: frameRotation = Frame.ROTATION_90; break;
      case 180: frameRotation = Frame.ROTATION_180; break;
      case 270: frameRotation = Frame.ROTATION_270; break;
      case 360: frameRotation = Frame.ROTATION_0; break;
      default: frameRotation = Frame.ROTATION_0; break;
    }

    synchronized (stateLock) {
      pending = false;
      pendingImage.timestamp = SystemClock.elapsedRealtime();
      pendingImage.frameRotation = frameRotation;
      pendingImage.frameId = frameId++;

      if(!pendingImage.capture(image))
        return;

      pending = true;

      stateLock.notifyAll();
    }
  }

  class Worker implements Runnable {
    @Override
    public void run() {
      synchronized(stateLock) {
        state = State.Running;
        requestedState = null;
        stateLock.notifyAll();
      }

      do {
        synchronized(stateLock) {
          while((!pending && state == State.Running) || (state == State.Paused)) {
            try { stateLock.wait(); }
            catch (InterruptedException ie) {
              Log.e("BarcodeScanner.Worker", "Thread interrupted.", ie);
              state = State.Stopped;
            }

            if(requestedState != null) {
              state = requestedState;
              requestedState = null;
              stateLock.notifyAll();
            }
          }

          if(state == State.Stopped)
            continue; // Will break in a minute

          // Swap
          BarcodeImage tempImage = processingImage;
          processingImage = pendingImage;
          pendingImage = tempImage;
          pending = false;
        }

        // Build the frame
        scaledImage.scaledFrom(processingImage);
//        BarcodeImage scaledImage = processingImage.scaledHalf(); // TODO: Eliminate alloc.

        Frame frame = new Frame.Builder()
           .setImageData(ByteBuffer.wrap(scaledImage.bytes), scaledImage.xSize, scaledImage.ySize, ImageFormat.NV21)
           .setId(processingImage.frameId)
           .setTimestampMillis(processingImage.timestamp)
           .setRotation(processingImage.frameRotation)
           .build();

        // Send it to the detector
        try { detector.receiveFrame(frame); }
        catch (Throwable t) {
          Log.e("BarcodeScanningThread", "Detector threw an exception.", t);
          synchronized (stateLock) {
            state = State.Stopped;
            stateLock.notifyAll();
          }
        }

      } while(state != State.Stopped);
    }
  }
}