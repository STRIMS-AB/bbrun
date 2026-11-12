#!/bin/sh
# Copies iconn, regl and DRef from the local Maven repository into libs/repo, the copy this
# project builds with where they are not installed: on GitHub, on anyone else's machine.
# They are not on Maven Central yet; when they are, libs/repo and this script go away.
#
#   (cd ../iconn && mvn install -DskipTests)
#   (cd ../regl/regl-java && mvn install -DskipTests) && (cd ../regl/regl-cli && mvn install -DskipTests)
#   (cd ../dref/dref-java && mvn install -DskipTests)
#   dist/vendor.sh
#
# Run it before a release, so that the released executable holds what was tested here.
# The versions are those of gradle.properties.
set -e
cd "$(dirname "$0")/.."
M2="${MAVEN_REPO:-$HOME/.m2/repository}"
REPO=libs/repo

version() { sed -n "s/^$1=//p" gradle.properties; }
ICONN=$(version iconnVersion)
REGL=$(version reglVersion)
DREF=$(version drefVersion)

rm -rf "$REPO"
copy() {   # group folder, artifact, version
    src="$M2/$1/$2/$3"
    [ -f "$src/$2-$3.pom" ] || { echo "$2 $3 is not in $M2: mvn install it first" >&2; exit 1; }
    mkdir -p "$REPO/$1/$2/$3"
    cp "$src/$2-$3.pom" "$REPO/$1/$2/$3/"
    if [ -f "$src/$2-$3.jar" ]; then cp "$src/$2-$3.jar" "$REPO/$1/$2/$3/"; fi
}

for artifact in iconn-parent iconn-bom iconn-api iconn-services iconn-core \
                iconn-connector-http iconn-connector-email iconn-connector-database; do
    copy dev/iconn "$artifact" "$ICONN"
done
copy dev/regl regl-java "$REGL"
copy dev/regl regl-cli "$REGL"
copy dev/dref dref-validator "$DREF"

find "$REPO" -type f | sort
du -sh "$REPO"
