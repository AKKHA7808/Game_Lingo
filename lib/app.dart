import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'providers/app_state_provider.dart';
import 'screens/home_screen.dart';

/// Root Material 3 widget for GameLingo with Modern Dark Theme.
class GameLingoApp extends StatelessWidget {
  final AppStateProvider? appStateProvider;

  const GameLingoApp({
    super.key,
    this.appStateProvider,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = ColorScheme.fromSeed(
      seedColor: const Color(0xFF6366F1),
      brightness: Brightness.dark,
      primary: const Color(0xFF6366F1),
      secondary: const Color(0xFF38BDF8),
      surface: const Color(0xFF0F172A),
    );

    return ChangeNotifierProvider<AppStateProvider>(
      create: (_) {
        final provider = appStateProvider ?? AppStateProvider();
        provider.initialize();
        return provider;
      },
      child: MaterialApp(
        title: 'GameLingo',
        debugShowCheckedModeBanner: false,
        themeMode: ThemeMode.dark,
        darkTheme: ThemeData(
          useMaterial3: true,
          brightness: Brightness.dark,
          colorScheme: colorScheme,
          scaffoldBackgroundColor: const Color(0xFF0B1120),
          appBarTheme: const AppBarTheme(
            backgroundColor: Color(0xFF0F172A),
            foregroundColor: Colors.white,
            elevation: 0,
            centerTitle: false,
          ),
          cardTheme: CardThemeData(
            color: const Color(0xFF1E293B),
            elevation: 0,
            margin: EdgeInsets.zero,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(18),
              side: const BorderSide(color: Color(0xFF334155), width: 1),
            ),
          ),
          filledButtonTheme: FilledButtonThemeData(
            style: FilledButton.styleFrom(
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
          ),
          outlinedButtonTheme: OutlinedButtonThemeData(
            style: OutlinedButton.styleFrom(
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
              side: const BorderSide(color: Color(0xFF475569)),
            ),
          ),
        ),
        home: const HomeScreen(),
      ),
    );
  }
}

