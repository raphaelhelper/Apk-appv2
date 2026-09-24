#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/src/main/java/com/qui/wordpopup app/src/main/res/drawable app/src/main/res/layout app/src/main/res/values
cp root_build.gradle.kts build.gradle.kts
cp app_build.gradle.kts app/build.gradle.kts
cp app_proguard-rules.pro app/proguard-rules.pro
cp app_src_main_AndroidManifest.xml app/src/main/AndroidManifest.xml
cp MainActivity.kt app/src/main/java/com/qui/wordpopup/MainActivity.kt
cp PopupService.kt app/src/main/java/com/qui/wordpopup/PopupService.kt
cp res_drawable_popup_bg.xml app/src/main/res/drawable/popup_bg.xml
cp res_layout_activity_main.xml app/src/main/res/layout/activity_main.xml
cp res_layout_popup_word.xml app/src/main/res/layout/popup_word.xml
cp res_values_styles.xml app/src/main/res/values/styles.xml
