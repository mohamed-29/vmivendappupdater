import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:vmivendappupdater/main.dart';

const channel = MethodChannel('com.example.vmivendappupdater/updater');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late List<MethodCall> calls;
  late String state;
  late String logs;
  late bool failClear;

  setUp(() {
    calls = [];
    state = 'idle';
    logs = '[]';
    failClear = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          switch (call.method) {
            case 'getStatus':
              return {
                'state': state,
                'message': 'Current state',
                'log': logs,
                'downloadProgress': 42,
              };
            case 'getConfig':
              return {
                'packageName': 'com.ivendapp',
                'checkUrl': 'https://example.com/check/',
                'checkIntervalMinutes': 360,
                'downloadUrl': '',
                'savedHash': '',
              };
            case 'clearHash':
              if (failClear) {
                throw PlatformException(
                  code: 'failure',
                  message: 'Storage unavailable',
                );
              }
              return true;
            default:
              return true;
          }
        });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  Future<void> open(WidgetTester tester) async {
    await tester.pumpWidget(const UpdaterApp());
    await tester.pump();
  }

  Future<void> close(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
  }

  testWidgets('corrupt status logs do not crash the screen', (tester) async {
    logs = '{invalid json';
    await open(tester);
    expect(find.text('No activity yet.'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await close(tester);
  });

  testWidgets('download progress renders a determinate percentage', (
    tester,
  ) async {
    state = 'downloading';
    await open(tester);
    expect(find.text('42%'), findsOneWidget);
    expect(
      tester
          .widget<LinearProgressIndicator>(find.byType(LinearProgressIndicator))
          .value,
      0.42,
    );
    await close(tester);
  });

  testWidgets('target version is editable and updates automatic check URL', (
    tester,
  ) async {
    await open(tester);
    await tester.tap(find.text('Config'));
    await tester.pumpAndSettle();
    final target = tester.widget<TextField>(find.byType(TextField).at(2));
    expect(target.enabled, isTrue);
    expect(target.controller!.text, 'com.ivendapp');
    await tester.enterText(find.byType(TextField).first, '');
    await tester.enterText(find.byType(TextField).at(2), 'ivend.cloud');
    expect(
      tester.widget<TextField>(find.byType(TextField).first).controller!.text,
      'https://machine.ivend.cloud/api/v1/updates/check/ivend.cloud/',
    );
    await tester.enterText(find.byType(TextField).at(2), 'release candidate 4');
    expect(
      tester.widget<TextField>(find.byType(TextField).first).controller!.text,
      'https://machine.ivend.cloud/api/v1/updates/check/release%20candidate%204/',
    );
    await close(tester);
  });

  testWidgets('save sends the configured URL and fixed package', (
    tester,
  ) async {
    await open(tester);
    await tester.tap(find.text('Config'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Save & Schedule'));
    await tester.tap(find.text('Save & Schedule'));
    await tester.pumpAndSettle();
    final saved = calls.singleWhere((call) => call.method == 'saveConfig');
    expect(saved.arguments['packageName'], 'com.ivendapp');
    expect(saved.arguments['checkUrl'], 'https://example.com/check/');
    await close(tester);
  });

  testWidgets('empty check URL prevents save', (tester) async {
    await open(tester);
    await tester.tap(find.text('Config'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).first, '');
    await tester.ensureVisible(find.text('Save & Schedule'));
    await tester.tap(find.text('Save & Schedule'));
    await tester.pumpAndSettle();
    expect(calls.where((call) => call.method == 'saveConfig'), isEmpty);
    await close(tester);
  });

  testWidgets(
    'force-update platform failure is shown without unhandled error',
    (tester) async {
      failClear = true;
      await open(tester);
      await tester.tap(find.text('Config'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Force Update'));
      await tester.tap(find.text('Force Update'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.textContaining('Storage unavailable'), findsOneWidget);
      await close(tester);
    },
  );
}
