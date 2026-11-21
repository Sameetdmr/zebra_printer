import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:zebra_printer/zebra_printer.dart';

void main() {
  group('PrintQueue', () {
    test('runs jobs for the same key one after another (FIFO)', () async {
      final queue = PrintQueue();
      final log = <String>[];
      final first = Completer<void>();

      final f1 = queue.add('A', () async {
        log.add('start 1');
        await first.future;
        log.add('end 1');
        return 1;
      });
      final f2 = queue.add('A', () async {
        log.add('start 2');
        return 2;
      });

      await Future<void>.delayed(Duration.zero);
      expect(log, ['start 1']);
      expect(queue.isActive('A'), isTrue);
      expect(queue.pendingCount('A'), 1);

      first.complete();
      expect(await f1, 1);
      expect(await f2, 2);
      expect(log, ['start 1', 'end 1', 'start 2']);
      expect(queue.isActive('A'), isFalse);
    });

    test('jobs for different keys run independently', () async {
      final queue = PrintQueue();
      final blockA = Completer<void>();
      final started = <String>[];

      final a = queue.add('A', () async {
        started.add('A');
        await blockA.future;
      });
      final b = queue.add('B', () async {
        started.add('B');
      });

      await b;
      expect(started, containsAll(['A', 'B']));
      expect(queue.activeKeys, ['A']);
      blockA.complete();
      await a;
    });

    test('a failing job does not block the next one', () async {
      final queue = PrintQueue();
      final f1 = queue.add<int>('A', () async => throw StateError('boom'));
      final f2 = queue.add<int>('A', () async => 2);

      await expectLater(f1, throwsStateError);
      expect(await f2, 2);
    });

    test('cancelPending fails waiting jobs with cancelled', () async {
      final queue = PrintQueue();
      final block = Completer<void>();
      final running = queue.add('A', () => block.future);
      final waiting = queue.add('A', () async => 'never');

      expect(queue.cancelPending('A'), 1);
      await expectLater(
        waiting,
        throwsA(
          isA<ZebraPrinterException>().having(
            (e) => e.code,
            'code',
            ZebraPrinterErrorCode.cancelled,
          ),
        ),
      );
      block.complete();
      await running;
    });
  });
}
