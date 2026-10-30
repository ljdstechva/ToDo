# The saved to-do list is JSON written by kotlinx.serialization; keep the model classes intact so an
# update can always read the file the previous version wrote.
-keep class com.ljdstechva.todo.data.** { *; }

# Glance builds a widget's tap actions by reflection (the checkbox's ToggleTodo): without its
# empty constructor a tapped checkbox shows ticked but the to-do stays open (seen in v1.0.16).
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
