#!/bin/bash
# Automates the creation of mipmap app icons using ImageMagick
BASE_ICON="app/src/main/res/raw/base_icon.png"
RES_DIR="app/src/main/res"

declare -A SIZES=( ["mdpi"]=48 ["hdpi"]=72 ["xhdpi"]=96 ["xxhdpi"]=144 ["xxxhdpi"]=192 )

for DENSITY in "${!SIZES[@]}"; do
    SIZE="${SIZES[$DENSITY]}"
    FOLDER="$RES_DIR/mipmap-$DENSITY"
    mkdir -p "$FOLDER"
    convert "$BASE_ICON" -resize "${SIZE}x${SIZE}" "$FOLDER/ic_launcher.png"
done
