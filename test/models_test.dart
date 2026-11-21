import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:zebra_printer/zebra_printer.dart';

void main() {
  group('PrintOptions', () {
    test('defaults are tuned for a shared printer', () {
      const o = PrintOptions();
      expect(o, PrintOptions.shared);
      expect(o.toMap(), {
        'maxWaitMs': 30000,
        'initialRetryDelayMs': 500,
        'maxRetryDelayMs': 4000,
        'jitter': true,
        'drainDelayMs': 400,
        'keepAliveMs': 0,
        'checkStatusBeforePrint': true,
        'waitForCompletion': false,
        'completionTimeoutMs': 5000,
        'insecure': false,
      });
    });

    test('dedicated keeps the link for 2 seconds', () {
      expect(PrintOptions.dedicated.keepAlive, const Duration(seconds: 2));
    });

    test('copyWith replaces only given fields', () {
      final o = PrintOptions.shared.copyWith(
        insecure: true,
        maxWait: const Duration(seconds: 60),
      );
      expect(o.insecure, isTrue);
      expect(o.maxWait, const Duration(seconds: 60));
      expect(o.drainDelay, PrintOptions.shared.drainDelay);
    });
  });

  group('ZebraPrinterException', () {
    test('maps platform codes and details', () {
      final e = ZebraPrinterException.fromPlatformException(
        PlatformException(
          code: 'PRINTER_BUSY',
          message: 'busy',
          details: {'attempts': 5, 'waitedMs': 29000},
        ),
      );
      expect(e.code, ZebraPrinterErrorCode.printerBusy);
      expect(e.attempts, 5);
      expect(e.waited, const Duration(seconds: 29));
      expect(e.isTransient, isTrue);
      expect(e.needsAttention, isFalse);
    });

    test('unknown codes keep the raw value', () {
      final e = ZebraPrinterException.fromPlatformException(
        PlatformException(code: 'SOMETHING_NEW'),
      );
      expect(e.code, ZebraPrinterErrorCode.unknown);
      expect(e.rawCode, 'SOMETHING_NEW');
    });

    test('paper out needs attention', () {
      final e = ZebraPrinterException(ZebraPrinterErrorCode.paperOut, 'x');
      expect(e.needsAttention, isTrue);
      expect(e.isTransient, isFalse);
    });
  });

  group('CalibrationType', () {
    test('command sets sense mode, calibrates and saves', () {
      final cmd = CalibrationType.bar.command;
      expect(cmd, contains('! U1 setvar "media.sense_mode" "bar"\r\n'));
      expect(cmd, contains('~JC'));
      expect(cmd, contains('^JUS'));
      expect(cmd, isNot(contains('wlan')));
      expect(cmd.startsWith('^XA'), isFalse);
    });
  });

  group('PrintProgress', () {
    test('parses waiting events from native', () {
      final p = PrintProgress.fromNative({
        'state': 'waiting',
        'percentage': 5,
        'attempt': 3,
        'waitedMs': 4200,
        'nextRetryMs': 2000,
        'jobId': 'j1',
        'address': 'AA:BB:CC:DD:EE:FF',
      });
      expect(p.state, PrintState.waiting);
      expect(p.isWaiting, isTrue);
      expect(p.isInProgress, isTrue);
      expect(p.attempt, 3);
      expect(p.waited, const Duration(milliseconds: 4200));
      expect(p.nextRetryIn, const Duration(seconds: 2));
      expect(p.jobId, 'j1');
    });

    test('parses error code and hold time', () {
      final err = PrintProgress.fromNative({
        'state': 'error',
        'errorCode': 'PAPER_OUT',
        'errorMessage': 'Paper out',
      });
      expect(err.errorCode, ZebraPrinterErrorCode.paperOut);

      final done = PrintProgress.fromNative({
        'state': 'completed',
        'percentage': 100,
        'holdMs': 1800,
      });
      expect(done.holdTime, const Duration(milliseconds: 1800));
    });
  });

  group('PrinterInfo', () {
    test('fromMap reads Link-OS fields', () {
      final info = PrinterInfo.fromMap({
        'model': 'ZQ620',
        'serialNumber': 'XXQLJ',
        'firmware': 'V85.20.19Z',
        'language': 'ZPL',
        'linkOsVersion': '7.4',
        'isProtectedMode': false,
      });
      expect(info.model, 'ZQ620');
      expect(info.language, PrinterLanguage.zpl);
      expect(info.linkOsVersion, '7.4');
      expect(info.isProtectedMode, isFalse);
    });

    test('fromString still parses the legacy format', () {
      final info = PrinterInfo.fromString(
        'Model: ZD421\nSerial: 123\nFirmware: V1\nLanguage: CPCL\n',
      );
      expect(info.model, 'ZD421');
      expect(info.language, PrinterLanguage.cpcl);
    });
  });

  group('ZebraPrinterStatus', () {
    test('reads error code when status could not be read', () {
      final s = ZebraPrinterStatus.fromNative({
        'isConnected': false,
        'errorCode': 'PRINTER_UNREACHABLE',
        'error': 'Printer is unreachable',
      });
      expect(s.errorCode, ZebraPrinterErrorCode.printerUnreachable);
      expect(s.canPrint, isFalse);
    });
  });
}
