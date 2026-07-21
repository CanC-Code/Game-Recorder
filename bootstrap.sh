#!/bin/bash
# Generates the Gradle wrapper files required for CI/CD builds
gradle wrapper --gradle-version 8.9 --distribution-type bin
chmod +x gradlew
