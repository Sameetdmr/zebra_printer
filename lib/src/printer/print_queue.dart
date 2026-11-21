import 'dart:async';
import 'dart:collection';

import '../models/zebra_printer_exception.dart';

/// Per-printer FIFO queue: jobs for the same printer run one after another,
/// jobs for different printers run independently.
///
/// Only coordinates jobs inside this app. Coordination between devices
/// happens on the native side (back-off and retry while the printer is busy).
class PrintQueue {
  final Map<String, _PrinterLane> _lanes = {};

  /// Adds [task] to the queue of [key] and completes with its result.
  Future<T> add<T>(String key, Future<T> Function() task, {String? label}) {
    final lane = _lanes.putIfAbsent(key, () => _PrinterLane(key));
    final job = _Job<T>(task, label);
    lane.pending.add(job);
    _pump(lane);
    return job.completer.future;
  }

  /// Is a job running for [key]?
  bool isActive(String key) => _lanes[key]?.active != null;

  /// Number of jobs waiting (not running) for [key].
  int pendingCount(String key) => _lanes[key]?.pending.length ?? 0;

  /// When the running job for [key] started (null when idle).
  DateTime? activeSince(String key) => _lanes[key]?.activeSince;

  /// Keys that currently run a job.
  List<String> get activeKeys =>
      _lanes.values.where((l) => l.active != null).map((l) => l.key).toList();

  /// Removes waiting jobs (running jobs cannot be interrupted). Their futures
  /// fail with [ZebraPrinterErrorCode.cancelled]. Returns the number removed.
  int cancelPending([String? key]) {
    final lanes = key == null ? _lanes.values.toList() : [_lanes[key]];
    var removed = 0;
    for (final lane in lanes) {
      if (lane == null) continue;
      while (lane.pending.isNotEmpty) {
        lane.pending.removeFirst().cancel();
        removed++;
      }
    }
    return removed;
  }

  void _pump(_PrinterLane lane) {
    if (lane.active != null || lane.pending.isEmpty) return;
    final job = lane.pending.removeFirst();
    lane.active = job;
    lane.activeSince = DateTime.now();
    job.run().whenComplete(() {
      lane.active = null;
      lane.activeSince = null;
      if (lane.pending.isEmpty) {
        _lanes.remove(lane.key);
      } else {
        _pump(lane);
      }
    });
  }
}

class _PrinterLane {
  final String key;
  final Queue<_Job<dynamic>> pending = Queue();
  _Job<dynamic>? active;
  DateTime? activeSince;

  _PrinterLane(this.key);
}

class _Job<T> {
  final Future<T> Function() task;
  final String? label;
  final Completer<T> completer = Completer<T>();

  _Job(this.task, this.label);

  Future<void> run() async {
    try {
      completer.complete(await task());
    } catch (e, st) {
      completer.completeError(e, st);
    }
  }

  void cancel() {
    completer.completeError(
      ZebraPrinterException(
        ZebraPrinterErrorCode.cancelled,
        'Job ${label ?? ''} was cancelled before it started'.replaceAll(
          '  ',
          ' ',
        ),
      ),
    );
  }
}
