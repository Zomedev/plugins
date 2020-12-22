package io.flutter.plugins.camera.barcodes;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.barcode.Barcode;

import io.flutter.plugin.common.EventChannel;

import java.util.HashMap;
import java.util.Map;

public class BarcodeTracker extends Tracker<Barcode>
{
  private int id;
  private EventChannel.EventSink sink;

  public BarcodeTracker(EventChannel.EventSink sink) {
    this.sink = sink;
  }

  @Override
  public void onNewItem(int id, Barcode barcode)
  {
    this.id = id;
    super.onNewItem(id, barcode);

    new Handler(Looper.getMainLooper()).post(new Runnable() {
      @Override
      public void run() {
        Map<String, Object> event = new HashMap<>();
        event.put("id", id);
        event.put("value", barcode.rawValue);
        sink.success(event);
      }
    });

//    Map<String, Object> event = new HashMap<>();
//    event.put("id", id);
//    event.put("value", barcode.rawValue);
//    sink.success(event);
  }

  // @Override
  // public void onUpdate(Detector.Detections<Barcode> detections, Barcode barcode)
  // {
  // 	super.onUpdate(detections, barcode);
  // }

  // @Override
  // public void onMissing(Detector.Detections<Barcode> detections)
  // {
  // 	super.onMissing(detections);
  // }

  @Override
  public void onDone()
  {
    new Handler(Looper.getMainLooper()).post(new Runnable() {
      @Override
      public void run() {
        Map<String, Object> event = new HashMap<>();
        event.put("id", id);
        event.put("value", null);
        sink.success(event);
      }
    });
//
//    Map<String, Object> event = new HashMap<>();
//    event.put("id", id);
//    event.put("value", null);
//    sink.success(event);

    super.onDone();
  }
}