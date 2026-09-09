#!/usr/bin/env bash
# 26.2 dev mappings for environments where Mojang publishes none.
#
# In the wild, `loom.officialMojangMappings()` works for 26.2 (Mojang ships
# client mappings, real 26.2 mods build against them). This environment's
# version manifest carries no client_mappings for 26.2 — but 26.2's client jar
# is UNOBFUSCATED (real class names), so a class-level IDENTITY mapping
# (official == intermediary == named) is all Loom needs to compile against the
# real names and emit a real-named jar (verified: output matches ModMenu, no
# class_NNNN intermediary refs).
#
# This script generates that identity mapping from the Loom-cached 26.2 merged
# jar and installs it as net.fabricmc:intermediary:26.2 into the local Maven
# repo, which build.gradle.kts points `mappings(...)` at. Run once before the
# first build (and again if the Gradle cache is wiped).
set -euo pipefail

VER="${1:-26.2}"
JAR="$(find "$HOME/.gradle/caches/fabric-loom/$VER" -name 'minecraft-merged.jar' 2>/dev/null | head -1)"
if [ -z "$JAR" ]; then
  echo "No cached minecraft-merged.jar for $VER — run './gradlew build' once first"
  echo "(it fails at mappings, but downloads the jar), then re-run this."
  exit 1
fi

WORK="$(mktemp -d)"; mkdir -p "$WORK/mappings"
python3 - "$JAR" > "$WORK/mappings/mappings.tiny" <<'PY'
import sys, zipfile
classes = [n[:-6] for n in zipfile.ZipFile(sys.argv[1]).namelist()
           if n.endswith('.class') and not n.startswith('META-INF')]
print("tiny\t2\t0\tofficial\tintermediary\tnamed")
for c in classes:
    print(f"c\t{c}\t{c}\t{c}")
PY

DEST="$HOME/.m2/repository/net/fabricmc/intermediary/$VER"
mkdir -p "$DEST"
( cd "$WORK" && jar cf "$DEST/intermediary-$VER.jar" mappings/mappings.tiny )
cat > "$DEST/intermediary-$VER.pom" <<EOF
<project><modelVersion>4.0.0</modelVersion><groupId>net.fabricmc</groupId><artifactId>intermediary</artifactId><version>$VER</version><packaging>jar</packaging></project>
EOF
rm -rf "$WORK"
echo "installed identity mappings for $VER ($(unzip -l "$DEST/intermediary-$VER.jar" | grep -c mappings.tiny) file)"
