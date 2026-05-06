import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const _channel = MethodChannel('com.example.vmivendappupdater/updater');

// Base URL of the check endpoint — set the package name to complete it
const String _checkBaseUrl = 'https://machine.ivend.cloud/api/v1/updates/check/';

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
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
      ),
      home: const ConfigScreen(),
    );
  }
}

class ConfigScreen extends StatefulWidget {
  const ConfigScreen({super.key});

  @override
  State<ConfigScreen> createState() => _ConfigScreenState();
}

class _ConfigScreenState extends State<ConfigScreen> {
  final _checkUrlCtrl = TextEditingController();
  final _downloadUrlCtrl = TextEditingController();
  final _packageCtrl = TextEditingController();
  String _savedHash = '';
  String _status = '';
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _loadConfig();
  }

  @override
  void dispose() {
    _checkUrlCtrl.dispose();
    _downloadUrlCtrl.dispose();
    _packageCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadConfig() async {
    try {
      final config =
          await _channel.invokeMapMethod<String, String>('getConfig');
      if (config != null && mounted) {
        setState(() {
          _checkUrlCtrl.text = config['checkUrl'] ?? '';
          _downloadUrlCtrl.text = config['downloadUrl'] ?? '';
          _packageCtrl.text = config['packageName'] ?? '';
          _savedHash = config['savedHash'] ?? '';
        });
      }
    } catch (_) {}
  }

  Future<void> _save() async {
    final url = _checkUrlCtrl.text.trim();
    final pkg = _packageCtrl.text.trim();
    if (url.isEmpty || pkg.isEmpty) {
      setState(() => _status = 'Check URL and Package Name are required.');
      return;
    }
    setState(() { _busy = true; _status = ''; });
    try {
      await _channel.invokeMethod('saveConfig', {
        'checkUrl': url,
        'packageName': pkg,
        'downloadUrl': _downloadUrlCtrl.text.trim(),
      });
      setState(() => _status = 'Saved. Background check scheduled (every 6 hours).');
    } catch (e) {
      setState(() => _status = 'Error: $e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _checkNow() async {
    setState(() { _busy = true; _status = 'Check triggered...'; });
    try {
      await _channel.invokeMethod('checkNow');
      setState(() => _status = 'Check dispatched. If hash differs, download starts in background.');
    } catch (e) {
      setState(() => _status = 'Error: $e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _forceUpdate() async {
    await _channel.invokeMethod('clearHash');
    setState(() {
      _savedHash = '';
      _status = 'Hash cleared — next check will force a fresh download.';
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('VM iVend Updater'),
        backgroundColor: theme.colorScheme.inversePrimary,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _field(
              controller: _checkUrlCtrl,
              label: 'Check URL *',
              hint: 'https://your-server/api/check-hash',
              keyboard: TextInputType.url,
            ),
            const SizedBox(height: 14),
            _field(
              controller: _downloadUrlCtrl,
              label: 'Download URL (leave blank if server returns it)',
              hint: 'https://your-server/app.apk',
              keyboard: TextInputType.url,
            ),
            const SizedBox(height: 14),
            _field(
              controller: _packageCtrl,
              label: 'Target Package Name *',
              hint: 'com.example.kioskapp',
              onChanged: (val) {
                // Auto-fill check URL if it's empty or still the default pattern
                final trimmed = val.trim();
                final current = _checkUrlCtrl.text;
                final isDefaultPattern = current.isEmpty ||
                    current.startsWith(_checkBaseUrl);
                if (isDefaultPattern && trimmed.isNotEmpty) {
                  _checkUrlCtrl.text = '$_checkBaseUrl$trimmed/';
                }
              },
            ),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: _busy ? null : _save,
              child: const Text('Save & Schedule Background Checks'),
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
            if (_savedHash.isNotEmpty) ...[
              const SizedBox(height: 20),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: theme.colorScheme.surfaceVariant,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Saved hash', style: theme.textTheme.labelSmall),
                    const SizedBox(height: 4),
                    Text(
                      _savedHash.length > 20
                          ? '${_savedHash.substring(0, 20)}...'
                          : _savedHash,
                      style: theme.textTheme.bodySmall?.copyWith(
                        fontFamily: 'monospace',
                      ),
                    ),
                  ],
                ),
              ),
            ],
            if (_status.isNotEmpty) ...[
              const SizedBox(height: 14),
              Text(
                _status,
                style: TextStyle(
                  color: _status.startsWith('Error')
                      ? theme.colorScheme.error
                      : theme.colorScheme.primary,
                  fontSize: 13,
                ),
              ),
            ],
            const SizedBox(height: 30),
            Text(
              'Server response format (JSON):\n'
              '{ "hash": "sha256hex", "url": "https://..." }\n\n'
              'Or plain text: just the SHA-256 hex string.\n'
              'If plain text, set Download URL above.\n\n'
              'Background check runs every 6 hours.\n'
              'Install is silent via root. Main app relaunches after install.',
              style: theme.textTheme.bodySmall?.copyWith(color: Colors.grey),
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
  }) {
    return TextField(
      controller: controller,
      keyboardType: keyboard,
      autocorrect: false,
      onChanged: onChanged,
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        border: const OutlineInputBorder(),
      ),
    );
  }
}
