# Xposed hook code (Java) is entered by name from assets/xposed_init and through anonymous
# XC_MethodHook callbacks: keep every class and member of the package, unrenamed.
# Only the Kotlin/Compose UI package and the generated R classes may be optimized
# (R stays out so resource shrinking can still see which resources are really used).
-keep class !io.github.qishui.automix.ui.**, !io.github.qishui.automix.R, !io.github.qishui.automix.R$*, io.github.qishui.automix.** { *; }

# Xposed API 82 is compileOnly; LSPosed provides it at runtime.
-dontwarn de.robv.android.xposed.**
