#!/bin/sh
# Publishes org.bbrun:bbrun-core to Maven Central through the Central Portal.
#
#   dist/central.sh              # builds build/central-bundle.zip, uploads nothing
#   dist/central.sh --upload     # builds it and uploads it for publication
#
# Central requires signatures: give the key as ORG_GRADLE_PROJECT_signingKey (the armored
# private key) and ORG_GRADLE_PROJECT_signingPassword. The upload needs a user token of the
# Portal, where the namespace org.bbrun is verified: CENTRAL_TOKEN_USER and
# CENTRAL_TOKEN_PASSWORD. iconn, regl and DRef must be on Maven Central in the versions of
# gradle.properties: bbrun-core depends on them.
set -e
cd "$(dirname "$0")/.."

rm -rf bbrun-core/build/central build/central-bundle.zip
./gradlew -q :bbrun-core:publishMavenJavaPublicationToCentralRepository

# A bundle is the files of the version: no repository index, no checksum of a signature
find bbrun-core/build/central -name 'maven-metadata.xml*' -delete
find bbrun-core/build/central \( -name '*.asc.md5' -o -name '*.asc.sha1' -o -name '*.asc.sha256' -o -name '*.asc.sha512' \) -delete

mkdir -p build
(cd bbrun-core/build/central && zip -q -r ../../../build/central-bundle.zip .)
unzip -l build/central-bundle.zip | awk 'NR > 3 && $4 != "" && $4 !~ /\/$/ { print "  " $4 }'

if ! unzip -l build/central-bundle.zip | grep -q '\.jar\.asc$'; then
    echo "The bundle is not signed: Maven Central will refuse it. Set ORG_GRADLE_PROJECT_signingKey and ORG_GRADLE_PROJECT_signingPassword." >&2
    [ "$1" = "--upload" ] && exit 1
fi

if [ "$1" = "--upload" ]; then
    [ -n "$CENTRAL_TOKEN_USER" ] && [ -n "$CENTRAL_TOKEN_PASSWORD" ] || { echo "CENTRAL_TOKEN_USER and CENTRAL_TOKEN_PASSWORD are not set" >&2; exit 1; }
    TOKEN=$(printf '%s:%s' "$CENTRAL_TOKEN_USER" "$CENTRAL_TOKEN_PASSWORD" | base64 | tr -d '\n')
    curl --fail --silent --show-error --request POST \
        --header "Authorization: Bearer $TOKEN" \
        --form bundle=@build/central-bundle.zip \
        "https://central.sonatype.com/api/v1/publisher/upload?name=bbrun-core&publishingType=AUTOMATIC"
    echo
    echo "Uploaded: the line above is the deployment id; its state is in the Central Portal."
fi
