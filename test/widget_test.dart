import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gamelingo/app.dart';
import 'package:gamelingo/models/translation_model_status.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  testWidgets('HomeScreen displays GameLingo header, controls, and status cards',
      (WidgetTester tester) async {
    await tester.pumpWidget(const GameLingoApp());
    await tester.pumpAndSettle();

    expect(find.text('GameLingo'), findsWidgets);
    expect(find.text('Start Floating Bubble'), findsOneWidget);
    expect(find.text('Stop Floating Bubble'), findsOneWidget);
    expect(find.text('Target Translation Language'), findsOneWidget);

    await tester.scrollUntilVisible(
      find.text('System & Permission Status'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('System & Permission Status'), findsOneWidget);

    await tester.scrollUntilVisible(
      find.text('On-Device Translation Models'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('On-Device Translation Models'), findsOneWidget);
  });

  testWidgets('LanguageSelector switches between Japanese -> Thai and English',
      (WidgetTester tester) async {
    await tester.pumpWidget(const GameLingoApp());
    await tester.pumpAndSettle();

    expect(find.text('Japanese → Thai'), findsWidgets);

    await tester.ensureVisible(find.text('Japanese → English'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Japanese → English'));
    await tester.pumpAndSettle();

    final prefs = await SharedPreferences.getInstance();
    expect(
      prefs.getString('gamelingo_target_language'),
      TargetLanguage.english.code,
    );
  });

  testWidgets('Navigates to SettingsScreen and PermissionScreen',
      (WidgetTester tester) async {
    await tester.pumpWidget(const GameLingoApp());
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('Settings'));
    await tester.pumpAndSettle();
    expect(find.text('Settings & Translation Models'), findsOneWidget);

    await tester.pageBack();
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('Permission Setup'));
    await tester.pumpAndSettle();
    expect(find.text('Permission Setup'), findsOneWidget);
    expect(find.text('Display over other apps'), findsOneWidget);
  });
}
