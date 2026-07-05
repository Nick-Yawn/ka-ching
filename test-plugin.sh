#!/bin/bash
# RuneLite Plugin Test Launcher
# This script builds and launches the plugin in RuneLite

echo "Building plugin..."
./gradlew shadowJar

if [ $? -eq 0 ]; then
    echo "Launching RuneLite with Ka-Ching! plugin..."
    java -ea \
        --add-exports java.desktop/com.apple.eawt=ALL-UNNAMED \
        --add-exports java.desktop/com.apple.eio=ALL-UNNAMED \
        -jar build/libs/ka-ching-1.0-SNAPSHOT-all.jar
else
    echo "Build failed. Please check the errors above."
    exit 1
fi
