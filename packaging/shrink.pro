-keepclassmembers class com.volmit.inscribe.config.** { <fields>; }
-keepclassmembers class com.volmit.inscribe.command.CommandService$Commands {
    com.volmit.inscribe.command.CommandService$DebugCommands debug;
}
-keep class com.volmit.inscribe.metrics.Metrics { *; }
-keep class com.volmit.inscribe.metrics.Metrics$* { *; }
