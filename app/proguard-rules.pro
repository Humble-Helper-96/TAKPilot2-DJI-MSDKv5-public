# TAKPilot2 DJI MSDKv5 — project keep rules.
#
# `minifyEnabled true` runs R8 even with no project rules file (the DJI AAR ships its own
# consumer rules and AGP contributes its defaults), so a missing file is easy to overlook until
# something gets stripped in the field. Keep this file even when it is nearly empty: its job is
# to be the place a keep rule goes.

# --- Reflection surfaces --------------------------------------------------------------------
# Room generates implementations by name and Gson-style reflection is used by some DJI paths;
# both ship their own consumer rules, so nothing is repeated here.

# --- Crash readability ----------------------------------------------------------------------
# Line numbers survive obfuscation so a fielded stack trace can be mapped back with the
# mapping file the release build copies to releases/ (see app/build.gradle). Without these two,
# a trace deobfuscates to method names with no line — enough to find the function, not enough
# to find the statement.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
