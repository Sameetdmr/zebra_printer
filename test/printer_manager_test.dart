import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:zebra_printer/zebra_printer.dart';

const _channel = MethodChannel('com.sameetdmr.zebra_printer/zebra_print');
const _mac = 'AC:3F:A4:12:34:56';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final printer = PrinterManager();
  final calls = <MethodCall>[];

  /// Per-method handler; default returns a success value.
  late Future<Object?> Function(MethodCall call) handler;

  setUp(() {
    calls.clear();
    printer.defaultOptions = PrintOptions.shared;
    printer.onPrintProgress = null;
    handler = (call) async => call.method == 'printLabel' ? 'ok' : true;
    messenger.setMockMethodCallHandler(_channel, (call) {
      calls.add(call);
      return handler(call);
    });
  });

  tearDown(() => messenger.setMockMethodCallHandler(_channel, null));

  Map<dynamic, dynamic> argsOf(MethodCall c) => c.arguments as Map;

  test('safePrint wraps ZPL and sends options + jobId', () async {
    await printer.safePrint(_mac, '^FDHello^FS');

    expect(calls, hasLength(1));
    final args = argsOf(calls.single);
    expect(calls.single.method, 'printLabel');
    expect(args['address'], _mac);
    expect(args['data'], '^XA^FDHello^FS^XZ');
    expect(args['jobId'], isA<String>());
    expect(args['options'], PrintOptions.shared.toMap());
  });

  test('per-call options override the defaults', () async {
    await printer.safePrint(
      _mac,
      '^XA^XZ',
      options: const PrintOptions(insecure: true),
    );
    expect((argsOf(calls.single)['options'] as Map)['insecure'], isTrue);
  });

  test('jobs for the same printer are queued, not rejected', () async {
    final release = Completer<void>();
    handler = (call) async {
      if (calls.length == 1) await release.future;
      return 'ok';
    };

    final first = printer.safePrint(_mac, '^XA1^XZ');
    // Same printer, different MAC spelling: must share the queue
    final second = printer.safePrint('ac3fa4123456', '^XA2^XZ');

    await Future<void>.delayed(const Duration(milliseconds: 10));
    expect(calls, hasLength(1), reason: 'second job waits in the queue');
    expect(printer.isPrinterLocked(_mac), isTrue);
    expect(printer.pendingJobCount(_mac), 1);

    release.complete();
    await Future.wait([first, second]);
    expect(calls.map((c) => argsOf(c)['data']), ['^XA1^XZ', '^XA2^XZ']);
    expect(printer.isPrinterLocked(_mac), isFalse);
  });

  test('platform errors become typed ZebraPrinterException', () async {
    handler =
        (call) async =>
            throw PlatformException(
              code: 'PRINTER_BUSY',
              message: 'Printer is busy',
              details: {'attempts': 7, 'waitedMs': 30000},
            );

    await expectLater(
      printer.safePrint(_mac, '^XA^XZ'),
      throwsA(
        isA<ZebraPrinterException>()
            .having((e) => e.code, 'code', ZebraPrinterErrorCode.printerBusy)
            .having((e) => e.attempts, 'attempts', 7),
      ),
    );
  });

  test('empty data is rejected before reaching native', () async {
    await expectLater(
      printer.safePrint(_mac, ''),
      throwsA(isA<ZebraPrinterException>()),
    );
    expect(calls, isEmpty);
  });

  test('calibratePrinter sends the raw command without ^XA wrapping', () async {
    await printer.calibratePrinter(_mac, CalibrationType.gap);
    final call = calls.single;
    expect(call.method, 'sendCommand');
    expect(argsOf(call)['data'], CalibrationType.gap.command);
  });

  test('safeBatchPrint wraps every label', () async {
    handler = (call) async => 'ok';
    await printer.safeBatchPrint(_mac, ['^FD1^FS', '^XA^FD2^FS^XZ']);
    expect(argsOf(calls.single)['dataList'], [
      '^XA^FD1^FS^XZ',
      '^XA^FD2^FS^XZ',
    ]);
  });

  test('status/info calls use a shorter wait budget by default', () async {
    handler = (call) async => {'model': 'ZQ620', 'language': 'ZPL'};
    final info = await printer.getPrinterInfo(_mac);
    expect(info.model, 'ZQ620');
    expect((argsOf(calls.single)['options'] as Map)['maxWaitMs'], 10000);
  });

  test('getZebraPrinterStatus never throws', () async {
    handler =
        (call) async =>
            throw PlatformException(code: 'PERMISSION_DENIED', message: 'no');
    final status = await printer.getZebraPrinterStatus(_mac);
    expect(status.errorCode, ZebraPrinterErrorCode.permissionDenied);
    expect(status.canPrint, isFalse);
  });

  test('progress events from native reach onPrintProgress', () async {
    final events = <PrintProgress>[];
    printer.onPrintProgress = events.add;

    await messenger.handlePlatformMessage(
      _channel.name,
      _channel.codec.encodeMethodCall(
        const MethodCall('onPrintProgress', {
          'state': 'waiting',
          'percentage': 5,
          'attempt': 2,
          'address': _mac,
        }),
      ),
      (_) {},
    );

    expect(events.single.state, PrintState.waiting);
    expect(events.single.attempt, 2);
    expect(printer.currentPrintPercentage, 5);
  });

  test('extractFontName reads the ~DY header', () {
    final data = Uint8List.fromList('~DYE:ARIAL,B,T,1000,,'.codeUnits);
    expect(PrinterManager.extractFontName(data), 'ARIAL');
    expect(
      PrinterManager.extractFontName(Uint8List.fromList([0, 1, 0, 0])),
      'font',
    );
  });
}
