import 'dart:math';

import 'package:flutter/material.dart';
import 'package:zebra_printer/zebra_printer.dart';

/// Field test for a shared printer.
///
/// Start it on several devices at the same time. Each device prints N small
/// labels and records how long the job took, how long the printer's Bluetooth
/// link was held and how many connection attempts were needed.
class StressTestPage extends StatefulWidget {
  final PrinterManager printerManager;
  final String address;
  final PrintOptions options;

  const StressTestPage({
    super.key,
    required this.printerManager,
    required this.address,
    required this.options,
  });

  @override
  State<StressTestPage> createState() => _StressTestPageState();
}

class _RunResult {
  final int index;
  final Duration total;
  final Duration? hold;
  final int attempts;
  final ZebraPrinterErrorCode? error;

  _RunResult(this.index, this.total, this.hold, this.attempts, this.error);
}

class _StressTestPageState extends State<StressTestPage> {
  final _random = Random();
  final List<_RunResult> _results = [];
  int _count = 10;
  bool _randomPause = true;
  bool _running = false;
  String _live = '';

  // Filled by onPrintProgress for the running job
  Duration? _lastHold;
  int _lastAttempt = 1;

  void Function(PrintProgress)? _previousListener;

  @override
  void initState() {
    super.initState();
    _previousListener = widget.printerManager.onPrintProgress;
    widget.printerManager.onPrintProgress = (p) {
      if (p.state == PrintState.waiting) _lastAttempt = (p.attempt ?? 0) + 1;
      if (p.state == PrintState.completed) _lastHold = p.holdTime;
      if (mounted) {
        setState(() {
          _live =
              p.isWaiting
                  ? 'waiting (attempt ${p.attempt}, ${p.waited?.inSeconds ?? 0}s)'
                  : p.state.name;
        });
      }
    };
  }

  @override
  void dispose() {
    widget.printerManager.onPrintProgress = _previousListener;
    super.dispose();
  }

  Future<void> _run() async {
    setState(() {
      _running = true;
      _results.clear();
    });
    for (var i = 1; i <= _count && mounted && _running; i++) {
      if (_randomPause) {
        // Simulates users printing at random moments
        await Future<void>.delayed(
          Duration(milliseconds: 200 + _random.nextInt(1800)),
        );
      }
      _lastHold = null;
      _lastAttempt = 1;
      final sw = Stopwatch()..start();
      ZebraPrinterErrorCode? error;
      try {
        await widget.printerManager.safePrint(
          widget.address,
          '^XA^FO40,40^A0N,40,40^FDStress $i/$_count^FS'
          '^FO40,100^A0N,25,25^FD${DateTime.now()}^FS^XZ',
          options: widget.options,
        );
      } on ZebraPrinterException catch (e) {
        error = e.code;
        _lastAttempt = e.attempts ?? _lastAttempt;
      } catch (_) {
        error = ZebraPrinterErrorCode.unknown;
      }
      sw.stop();
      if (!mounted) return;
      setState(() {
        _results.add(_RunResult(i, sw.elapsed, _lastHold, _lastAttempt, error));
      });
    }
    if (mounted) setState(() => _running = false);
  }

  String _ms(Duration? d) => d == null ? '-' : '${d.inMilliseconds} ms';

  Widget _summary() {
    if (_results.isEmpty) return const SizedBox.shrink();
    final ok = _results.where((r) => r.error == null).toList();
    final holds = ok.map((r) => r.hold).whereType<Duration>().toList();
    final totals = ok.map((r) => r.total).toList();
    Duration avg(List<Duration> l) =>
        l.isEmpty
            ? Duration.zero
            : Duration(
              milliseconds:
                  l.map((d) => d.inMilliseconds).reduce((a, b) => a + b) ~/
                  l.length,
            );
    Duration maxOf(List<Duration> l) =>
        l.isEmpty ? Duration.zero : l.reduce((a, b) => a > b ? a : b);
    final errors = <String, int>{};
    for (final r in _results.where((r) => r.error != null)) {
      errors[r.error!.name] = (errors[r.error!.name] ?? 0) + 1;
    }
    return Card(
      margin: const EdgeInsets.all(8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Success: ${ok.length}/${_results.length}'),
            Text(
              'Printer held: avg ${_ms(avg(holds))}, max ${_ms(maxOf(holds))}',
            ),
            Text(
              'Job time: avg ${_ms(avg(totals))}, max ${_ms(maxOf(totals))}',
            ),
            Text(
              'Retried jobs: ${_results.where((r) => r.attempts > 1).length}',
            ),
            if (errors.isNotEmpty) Text('Errors: $errors'),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Shared printer stress test')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(12),
            child: Text(
              'Run this on several devices at the same time against '
              '${widget.address}.',
              textAlign: TextAlign.center,
            ),
          ),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Text('Labels:'),
              DropdownButton<int>(
                value: _count,
                items:
                    const [5, 10, 20, 50]
                        .map(
                          (n) => DropdownMenuItem(value: n, child: Text('$n')),
                        )
                        .toList(),
                onChanged: _running ? null : (v) => setState(() => _count = v!),
              ),
              const SizedBox(width: 16),
              const Text('Random pause'),
              Switch(
                value: _randomPause,
                onChanged:
                    _running ? null : (v) => setState(() => _randomPause = v),
              ),
            ],
          ),
          ElevatedButton.icon(
            onPressed: _running ? () => setState(() => _running = false) : _run,
            icon: Icon(_running ? Icons.stop : Icons.play_arrow),
            label: Text(_running ? 'Stop after current job' : 'Start'),
          ),
          if (_running) Text('Now: $_live'),
          _summary(),
          const Divider(),
          Expanded(
            child: ListView.builder(
              itemCount: _results.length,
              itemBuilder: (context, i) {
                final r = _results[_results.length - 1 - i];
                return ListTile(
                  dense: true,
                  leading: Icon(
                    r.error == null ? Icons.check_circle : Icons.error,
                    color: r.error == null ? Colors.green : Colors.red,
                  ),
                  title: Text(
                    '#${r.index}  total ${_ms(r.total)}  held ${_ms(r.hold)}',
                  ),
                  subtitle: Text(
                    'attempts ${r.attempts}'
                    '${r.error != null ? '  •  ${r.error!.name}' : ''}',
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
