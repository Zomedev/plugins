package io.flutter.plugins.camera.barcodes;

import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.barcode.Barcode;

import io.flutter.plugin.common.EventChannel;

public class BarcodeTrackerFactory implements MultiProcessor.Factory<Barcode>
{
  private EventChannel.EventSink sink;

  public void setSink(EventChannel.EventSink sink) {
    this.sink = sink;
  }

  @Override
  public Tracker<Barcode> create(Barcode barcode)
  {
    return new BarcodeTracker(sink);
  }
}