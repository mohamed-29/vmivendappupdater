import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/widgets.dart';

import 'package:vmivendappupdater/main.dart';

const updaterChannel = MethodChannel(
  'com.example.vmivendappupdater/updater',
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('renders updater status and configuration navigation', (
    WidgetTester tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(updaterChannel, (call) async {
      if (call.method == 'getStatus') {
        return <String, dynamic>{
          'state': 'idle',
          'message': 'Waiting for first check.',
          'statusTime': '0',
          'lastCheckTime': '0',
          'downloadProgress': -1,
          'lastInstalledVersion': '',
          'log': '[]',
        };
      }
      return null;
    });

    await tester.pumpWidget(const UpdaterApp());
    await tester.pump();

    expect(find.text('Status'), findsOneWidget);
    expect(find.text('Config'), findsOneWidget);
    expect(find.text('Waiting for first check.'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(updaterChannel, null);
  });
}
