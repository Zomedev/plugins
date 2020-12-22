
part of 'camera.dart';

class BarcodeScan {
  BarcodeScan._fromPlatformData(Map<dynamic, dynamic> data)
      : id = data['id'],
        value = data['value'];

  /// Unique id of detected barcoe.
  ///
  /// Uniquely identify barcodes scanned.
  final int id;

  /// The store text of the barcode
  ///
  /// The value store in the barcode scanned. If blank barcode was lost.
  final String value;
}