# DetAssist ProGuard Rules
# Keep all voice interaction service classes (required by OS binding)
-keep class com.detassist.DetAssistVoiceInteractionService { *; }
-keep class com.detassist.DetAssistVoiceInteractionSessionService { *; }
-keep class com.detassist.DetAssistVoiceInteractionSession { *; }
-keep class com.detassist.AudioListenerService { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the Application class
-keep class com.detassist.DetAssistApp { *; }

# Keep all ParsedCommand fields (used in reflection-free code but keep for safety)
-keep class com.detassist.RegexCommandParser$ParsedCommand { *; }
-keep class com.detassist.AppIndex$AppEntry { *; }
