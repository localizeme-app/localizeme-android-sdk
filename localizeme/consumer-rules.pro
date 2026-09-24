# The SDK maps resource ids back to names at runtime; keep the R class names.
-keepclassmembers class **.R$string { public static <fields>; }

# XML titles and hints in the app namespace are re-applied through these
# setters by name (AppCompat Toolbar, MaterialToolbar, CollapsingToolbarLayout,
# TextInputLayout), so R8 must not rename them.
-keepclassmembers class * extends android.view.View {
    public void setTitle(java.lang.CharSequence);
    public void setSubtitle(java.lang.CharSequence);
    public void setHint(java.lang.CharSequence);
}
