import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const _channel = MethodChannel('com.example.vmivendappupdater/updater');

void main() {
  runApp(const UpdaterApp());
}

class UpdaterApp extends StatelessWidget {
  const UpdaterApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'VM iVend Updater',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF3B4CCA)),
        useMaterial3: true,
        fontFamily: 'sans-serif',
      ),
      home: const UpdaterHome(),
    );
  }
}

// ─── Home shell with bottom nav ─────────────────────────────────────────────

class UpdaterHome extends StatefulWidget {
  const UpdaterHome({super.key});

  @override
  State<UpdaterHome> createState() => _UpdaterHomeState();
}

class _UpdaterHomeState extends State<UpdaterHome> {
  int _tab = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(
        index: _tab,
        children: const [StatusScreen(), ConfigScreen()],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) => setState(() => _tab = i),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.monitor_heart_outlined),
            selectedIcon: Icon(Icons.monitor_heart),
            label: 'Status',
          ),
          NavigationDestination(
            icon: Icon(Icons.settings_outlined),
            selectedIcon: Icon(Icons.settings),
            label: 'Config',
          ),
        ],
      ),
    );
  }
}

// ─── Status screen ──────────────────────────────────────────────────────────

class StatusScreen extends StatefulWidget {
  const StatusScreen({super.key});

  @override
  State<StatusScreen> createState() => _StatusScreenState();
}

class _StatusScreenState extends State<StatusScreen> {
  Timer? _timer;
  Map<String, dynamic> _status = {};
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    try {
      final raw = await _channel.invokeMapMethod<String, dynamic>('getStatus');
      if (raw != null && mounted) {
        setState(() {
          _status = Map<String, dynamic>.from(raw);
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return const _Splash();

    final state = _status['state'] as String? ?? 'idle';
    final message = _status['message'] as String? ?? 'Waiting for first check.';
    final progress = (_status['downloadProgress'] as int?) ?? -1;
    final lastCheck = _status['lastCheckTime'] as String? ?? '0';
    final lastVersion = _status['lastInstalledVersion'] as String? ?? '';
    final logJson = _status['log'] as String? ?? '[]';

    List<Map<String, String>> logs = [];
    try {
      final arr = jsonDecode(logJson) as List;
      logs = arr
          .map((e) => {'t': e['t'] as String, 'm': e['m'] as String})
          .toList()
          .reversed
          .toList();
    } catch (_) {}

    final lastCheckMs = int.tryParse(lastCheck) ?? 0;
    final lastCheckStr = lastCheckMs > 0
        ? _timeAgo(DateTime.fromMillisecondsSinceEpoch(lastCheckMs))
        : 'Never';

    return SafeArea(
      child: SingleChildScrollView(child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // ── Header ──────────────────────────────────────────────────────
          Container(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 16),
            color: Theme.of(context).colorScheme.surface,
            child: Row(
              children: [
                const Text(
                  'Live Status',
                  style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
                ),
                const Spacer(),
                // Live pulse dot when active
                if (_isActive(state))
                  Container(
                    width: 8,
                    height: 8,
                    decoration: BoxDecoration(
                      color: _stateColor(state),
                      shape: BoxShape.circle,
                    ),
                  ),
                if (_isActive(state)) const SizedBox(width: 6),
                if (_isActive(state))
                  Text(
                    'LIVE',
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w700,
                      color: _stateColor(state),
                    ),
                  ),
              ],
            ),
          ),
          // ── Status card ─────────────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: _StatusCard(
              state: state,
              message: message,
              progress: progress,
              lastCheck: lastCheckStr,
              lastVersion: lastVersion,
            ),
          ),
          // ── Log title ───────────────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Card(child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text('Kiosk\n${_status['kioskMessage'] ?? 'Checking kiosk setup…'}\n${_status['healthMessage'] ?? ''}\n\nUpdater self-update\n${_status['selfMessage'] ?? 'Waiting for check.'}'),
            )),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Card(child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text('Device usage\n${_status['resourceUsage'] ?? 'Waiting for CPU, RAM and storage sample…'}'),
            )),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
            child: Row(
              children: [
                const Text(
                  'Activity Log',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: Colors.black54,
                  ),
                ),
                const Spacer(),
                Text(
                  'Updates every 2s',
                  style: TextStyle(fontSize: 11, color: Colors.grey.shade400),
                ),
              ],
            ),
          ),
          // ── Log list ────────────────────────────────────────────────────
          Padding(
            padding: EdgeInsets.zero,
            child: logs.isEmpty
                ? Center(
                    child: Text(
                      'No activity yet.',
                      style: TextStyle(
                        color: Colors.grey.shade400,
                        fontSize: 13,
                      ),
                    ),
                  )
                : ListView.builder(
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                    itemCount: logs.length,
                    itemBuilder: (ctx, i) {
                      final entry = logs[i];
                      final isFirst = i == 0;
                      return Padding(
                        padding: const EdgeInsets.only(bottom: 4),
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              entry['t'] ?? '',
                              style: TextStyle(
                                fontSize: 11,
                                fontFamily: 'monospace',
                                color: Colors.grey.shade400,
                              ),
                            ),
                            const SizedBox(width: 10),
                            Expanded(
                              child: Text(
                                entry['m'] ?? '',
                                style: TextStyle(
                                  fontSize: 13,
                                  color: isFirst
                                      ? Colors.black87
                                      : Colors.black54,
                                  fontWeight: isFirst
                                      ? FontWeight.w500
                                      : FontWeight.normal,
                                ),
                              ),
                            ),
                          ],
                        ),
                      );
                    },
                  ),
          ),
        ],
      )),
    );
  }

  bool _isActive(String state) =>
      state == 'checking' ||
      state == 'downloading' ||
      state == 'verifying' ||
      state == 'installing';

  Color _stateColor(String state) {
    switch (state) {
      case 'up_to_date':
        return Colors.green;
      case 'updated':
        return Colors.blue;
      case 'error':
        return Colors.red;
      case 'checking':
      case 'verifying':
        return Colors.orange;
      case 'downloading':
      case 'installing':
        return Colors.blue;
      default:
        return Colors.grey;
    }
  }

  String _timeAgo(DateTime dt) {
    final diff = DateTime.now().difference(dt);
    if (diff.inSeconds < 60) return '${diff.inSeconds}s ago';
    if (diff.inMinutes < 60) return '${diff.inMinutes}m ago';
    if (diff.inHours < 24) return '${diff.inHours}h ago';
    return '${diff.inDays}d ago';
  }
}

// ─── Status card widget ──────────────────────────────────────────────────────

class _StatusCard extends StatelessWidget {
  const _StatusCard({
    required this.state,
    required this.message,
    required this.progress,
    required this.lastCheck,
    required this.lastVersion,
  });

  final String state;
  final String message;
  final int progress;
  final String lastCheck;
  final String lastVersion;

  @override
  Widget build(BuildContext context) {
    final color = _color();
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.07),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: color.withValues(alpha: 0.25)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              _icon(color),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  message,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: color.withValues(alpha: 0.9),
                  ),
                ),
              ),
            ],
          ),
          if (state == 'downloading' && progress >= 0) ...[
            const SizedBox(height: 10),
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: progress / 100,
                minHeight: 6,
                backgroundColor: color.withValues(alpha: 0.15),
                valueColor: AlwaysStoppedAnimation<Color>(color),
              ),
            ),
            const SizedBox(height: 4),
            Text(
              '$progress%',
              style: TextStyle(
                fontSize: 11,
                color: color,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
          if (state == 'checking' ||
              state == 'verifying' ||
              state == 'installing') ...[
            const SizedBox(height: 10),
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                minHeight: 6,
                backgroundColor: color.withValues(alpha: 0.15),
                valueColor: AlwaysStoppedAnimation<Color>(color),
              ),
            ),
          ],
          const SizedBox(height: 12),
          Row(
            children: [
              _meta('Last check', lastCheck),
              const SizedBox(width: 20),
              if (lastVersion.isNotEmpty) _meta('Installed', 'v$lastVersion'),
            ],
          ),
        ],
      ),
    );
  }

  Widget _icon(Color color) {
    final icon = switch (state) {
      'up_to_date' => Icons.check_circle_outline,
      'updated' => Icons.system_update_outlined,
      'error' => Icons.error_outline,
      'downloading' => Icons.download_outlined,
      'installing' => Icons.install_mobile_outlined,
      'checking' => Icons.cloud_sync_outlined,
      'verifying' => Icons.verified_outlined,
      _ => Icons.hourglass_empty_outlined,
    };
    return Icon(icon, color: color, size: 22);
  }

  Color _color() {
    return switch (state) {
      'up_to_date' => Colors.green.shade600,
      'updated' => Colors.blue.shade600,
      'error' => Colors.red.shade600,
      'checking' || 'verifying' => Colors.orange.shade600,
      'downloading' || 'installing' => Colors.blue.shade600,
      _ => Colors.grey.shade500,
    };
  }

  Widget _meta(String label, String value) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: const TextStyle(
            fontSize: 10,
            color: Colors.black38,
            fontWeight: FontWeight.w500,
          ),
        ),
        Text(
          value,
          style: const TextStyle(fontSize: 12, color: Colors.black54),
        ),
      ],
    );
  }
}

// ─── Splash ─────────────────────────────────────────────────────────────────

class _Splash extends StatelessWidget {
  const _Splash();

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          CircularProgressIndicator(strokeWidth: 2),
          SizedBox(height: 16),
          Text(
            'Loading status…',
            style: TextStyle(color: Colors.black38, fontSize: 13),
          ),
        ],
      ),
    );
  }
}

// ─── Config screen ──────────────────────────────────────────────────────────

class ConfigScreen extends StatefulWidget {
  const ConfigScreen({super.key});

  @override
  State<ConfigScreen> createState() => _ConfigScreenState();
}

class _ConfigScreenState extends State<ConfigScreen> {
  static const _automaticCheckUrlPrefix =
      'https://machine.ivend.cloud/api/v1/updates/check/';
  final _checkUrlCtrl = TextEditingController();
  final _downloadUrlCtrl = TextEditingController();
  final _packageCtrl = TextEditingController();
  String _savedHash = '';
  String _feedback = '';
  bool _isError = false;
  bool _busy = false;
  int _intervalMinutes = 360; // default 6 hours

  // Interval options: value in minutes → display label
  static const Map<int, String> _intervalOptions = {
    15: '15 minutes',
    30: '30 minutes',
    60: '1 hour',
    120: '2 hours',
    180: '3 hours',
    360: '6 hours',
    720: '12 hours',
    1440: '24 hours',
  };

  @override
  void initState() {
    super.initState();
    _packageCtrl.addListener(_updateAutomaticCheckUrl);
    _loadConfig();
  }

  void _updateAutomaticCheckUrl() {
    final packageName = _packageCtrl.text.trim();
    if (packageName.isEmpty) return;
    final automaticUrl =
        '$_automaticCheckUrlPrefix${Uri.encodeComponent(packageName)}/';
    final current = _checkUrlCtrl.text.trim();
    if (current.isEmpty || current.startsWith(_automaticCheckUrlPrefix)) {
      _checkUrlCtrl.text = automaticUrl;
    }
  }

  @override
  void dispose() {
    _packageCtrl.removeListener(_updateAutomaticCheckUrl);
    _checkUrlCtrl.dispose();
    _downloadUrlCtrl.dispose();
    _packageCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadConfig() async {
    try {
      final config = await _channel.invokeMapMethod<String, dynamic>(
        'getConfig',
      );
      if (config != null && mounted) {
        setState(() {
          _checkUrlCtrl.text = (config['checkUrl'] as String?) ?? '';
          _downloadUrlCtrl.text = (config['downloadUrl'] as String?) ?? '';
          _packageCtrl.text = (config['packageName'] as String?) ?? 'ivend.cloud';
          _updateAutomaticCheckUrl();
          _savedHash = (config['savedHash'] as String?) ?? '';
          _intervalMinutes = (config['checkIntervalMinutes'] as int?) ?? 360;
        });
      }
    } catch (_) {}
  }

  Future<void> _save() async {
    final url = _checkUrlCtrl.text.trim();
    final pkg = _packageCtrl.text.trim();
    if (url.isEmpty || pkg.isEmpty) {
      setState(() {
        _feedback = 'Check URL and Target Version Name are required.';
        _isError = true;
      });
      return;
    }
    setState(() {
      _busy = true;
      _feedback = '';
    });
    try {
      await _channel.invokeMethod('saveConfig', {
        'checkUrl': url,
        'packageName': pkg,
        'downloadUrl': _downloadUrlCtrl.text.trim(),
        'checkIntervalMinutes': _intervalMinutes,
      });
      if (!mounted) return;
      setState(() {
        _feedback =
            'Saved. Background check scheduled every ${_intervalOptions[_intervalMinutes] ?? '$_intervalMinutes min'}.';
        _isError = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _feedback = 'Error: $e';
        _isError = true;
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _checkNow() async {
    setState(() {
      _busy = true;
      _feedback = 'Check dispatched — watch Status tab for progress.';
      _isError = false;
    });
    try {
      await _channel.invokeMethod('checkNow');
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _feedback = 'Error: $e';
        _isError = true;
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _forceUpdate() async {
    try {
      await _channel.invokeMethod('clearHash');
      if (!mounted) return;
      setState(() {
        _savedHash = '';
        _feedback = 'Hash cleared — next check will force a fresh download.';
        _isError = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _feedback = 'Error: $e';
        _isError = true;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 32),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'Configuration',
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 20),
            _field(
              controller: _checkUrlCtrl,
              label: 'Check URL *',
              hint: 'https://machine.ivend.cloud/api/v1/updates/check/com.app/',
              keyboard: TextInputType.url,
            ),
            const SizedBox(height: 14),
            _field(
              controller: _downloadUrlCtrl,
              label: 'Download URL (leave blank — server returns it)',
              hint: 'https://server/app.apk',
              keyboard: TextInputType.url,
            ),
            const SizedBox(height: 14),
            _field(
              controller: _packageCtrl,
              label: 'Target Version Name *',
              hint: 'ivend.cloud',
            ),
            const SizedBox(height: 14),
            // ── Check interval dropdown ──
            InputDecorator(
              decoration: const InputDecoration(
                labelText: 'Check Interval',
                border: OutlineInputBorder(),
                contentPadding: EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 4,
                ),
                isDense: true,
              ),
              child: DropdownButtonHideUnderline(
                child: DropdownButton<int>(
                  value: _intervalOptions.containsKey(_intervalMinutes)
                      ? _intervalMinutes
                      : 360,
                  isExpanded: true,
                  isDense: true,
                  style: const TextStyle(fontSize: 14, color: Colors.black87),
                  items: _intervalOptions.entries.map((e) {
                    return DropdownMenuItem<int>(
                      value: e.key,
                      child: Text(e.value),
                    );
                  }).toList(),
                  onChanged: (val) {
                    if (val != null) setState(() => _intervalMinutes = val);
                  },
                ),
              ),
            ),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: _busy ? null : _save,
              child: const Text('Save & Schedule'),
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(
                  child: FilledButton.tonal(
                    onPressed: _busy ? null : _checkNow,
                    child: const Text('Check Now'),
                  ),
                ),
                const SizedBox(width: 10),
                OutlinedButton(
                  onPressed: _busy ? null : _forceUpdate,
                  child: const Text('Force Update'),
                ),
              ],
            ),
            if (_feedback.isNotEmpty) ...[
              const SizedBox(height: 14),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: _isError ? Colors.red.shade50 : Colors.green.shade50,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(
                    color: _isError
                        ? Colors.red.shade200
                        : Colors.green.shade200,
                  ),
                ),
                child: Text(
                  _feedback,
                  style: TextStyle(
                    fontSize: 13,
                    color: _isError
                        ? Colors.red.shade700
                        : Colors.green.shade700,
                  ),
                ),
              ),
            ],
            if (_savedHash.isNotEmpty) ...[
              const SizedBox(height: 16),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.grey.shade50,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.grey.shade200),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Saved hash',
                      style: TextStyle(fontSize: 10, color: Colors.black38),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      _savedHash.length > 24
                          ? '${_savedHash.substring(0, 24)}…'
                          : _savedHash,
                      style: const TextStyle(
                        fontSize: 12,
                        fontFamily: 'monospace',
                        color: Colors.black54,
                      ),
                    ),
                  ],
                ),
              ),
            ],
            const SizedBox(height: 24),
            Text(
              'Check runs every ${_intervalOptions[_intervalMinutes] ?? '$_intervalMinutes min'} when network is available.\n'
              'Install is silent via root — no prompt after first Magisk grant.\n'
              'Recovery uses the saved APK. A rejected recovery install triggers uninstall and reinstall. This removes private iVend data.\n'
              'Open the Status tab to watch live progress.',
              style: TextStyle(
                fontSize: 12,
                color: Colors.grey.shade400,
                height: 1.6,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _field({
    required TextEditingController controller,
    required String label,
    String hint = '',
    TextInputType keyboard = TextInputType.text,
    void Function(String)? onChanged,
    bool enabled = true,
  }) {
    return TextField(
      controller: controller,
      enabled: enabled,
      keyboardType: keyboard,
      autocorrect: false,
      onChanged: onChanged,
      style: const TextStyle(fontSize: 14),
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        hintStyle: const TextStyle(fontSize: 12),
        border: const OutlineInputBorder(),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 12,
          vertical: 10,
        ),
        isDense: true,
      ),
    );
  }
}
