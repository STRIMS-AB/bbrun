#!/bin/sh
# Builds the native bbrun executable for the machine it runs on: build/native/bbrun.
# One file, no Java needed to run it.
#
#   dist/native.sh              # build
#   dist/native.sh --check      # build, then run the examples with the executable
#
# Needs GraalVM's native-image: on the PATH, or GRAALVM_HOME, or an sdkman GraalVM
# (sdk install java <version>-graalce). The reflection and resource configuration the
# executable needs is in bbrun-cli/src/main/resources/META-INF/native-image/.
set -e
cd "$(dirname "$0")/.."

if [ -n "$GRAALVM_HOME" ] && [ -x "$GRAALVM_HOME/bin/native-image" ]; then
    NATIVE_IMAGE="$GRAALVM_HOME/bin/native-image"
elif command -v native-image >/dev/null 2>&1; then
    NATIVE_IMAGE=native-image
else
    NATIVE_IMAGE=$(ls -d "$HOME"/.sdkman/candidates/java/*graal*/bin/native-image 2>/dev/null | sort | tail -1)
fi
[ -n "$NATIVE_IMAGE" ] || { echo "native-image not found: install GraalVM (sdk install java <version>-graalce) or set GRAALVM_HOME" >&2; exit 1; }

./gradlew -q :bbrun-cli:installDist
LIB=bbrun-cli/build/install/bbrun/lib
mkdir -p build/native

# fastjson2's own native-image settings initialise it while the image is built, which on
# GraalVM 25 either fails the build or leaves a JSON reader that cannot read strings: they
# are left out, so the library starts when the executable does.
#
# The same library reads memory through sun.misc.Unsafe, which Java 24 and later report with
# four WARNING lines on every run: the image is built with that access allowed, so the
# executable prints only what bbrun prints.
#
# Only what the command runs is compiled in: the ANTLR tool and its libraries, which the
# grammar build puts on the classpath, are left out
CLASSPATH=""
for jar in "$LIB"/*.jar; do
    case "$(basename "$jar")" in
        antlr4-4*|ST4-*|antlr-runtime-3*|icu4j-*|org.abego.treelayout*) ;;
        *) CLASSPATH="$CLASSPATH:$jar" ;;
    esac
done

"$NATIVE_IMAGE" \
    -cp "${CLASSPATH#:}" \
    -o build/native/bbrun \
    -H:+ReportExceptionStackTraces \
    -march=compatibility \
    -J--sun-misc-unsafe-memory-access=allow \
    --exclude-config '.*fastjson2.*' 'META-INF/native-image/.*' \
    org.bbrun.cli.Main

ls -lh build/native/bbrun
build/native/bbrun --version

if [ "$1" = "--check" ]; then
    B="$PWD/build/native/bbrun"
    "$B" run examples/quickstart --no-color
    "$B" run examples/reference/testing/_reference/tests --no-color
    "$B" run examples/reference/integration --no-color
    "$B" envs -c examples/reference/testing/payments/bbrun.yaml
    "$B" models -c examples/testing/order-api/bbrun.yaml
    "$B" validate examples/quickstart/checkout.bbrun
    # --json prints JSON and nothing else, on either stream
    (cd examples/quickstart && "$B" checkout.bbrun --json > ../../build/native/result.json 2> ../../build/native/result.err)
    grep -q '"isSuccess":true' build/native/result.json || { echo "--json did not print a result" >&2; exit 1; }
    [ ! -s build/native/result.err ] || { echo "--json wrote to stderr:" >&2; cat build/native/result.err >&2; exit 1; }
    "$B" load examples/quickstart/checkout.bbrun -u 2 -i 20 --no-color > /dev/null
    echo "native check passed"
fi
