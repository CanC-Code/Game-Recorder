#!/bin/bash
# Generates the Gradle wrapper files required for CI/CD builds (using Gradle 8.11.1 for AGP compatibility)
gradle wrapper --gradle-version 8.11.1 --distribution-type bin
chmod +x gradlew
