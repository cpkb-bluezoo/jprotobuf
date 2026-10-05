#!/usr/bin/env bash
# Uploads release jars to GitHub Packages (Maven registry). Intended to run
# after the same-version build used for Maven Central - typically immediately
# after publish-to-central.sh in CI, which leaves dist/jprotobuf-$VERSION.* in
# place. Can also build locally when those files are missing.
#
# Usage (CI - settings.xml from actions/setup-java with server-id github):
#   GITHUB_TOKEN=... GITHUB_ACTOR=... ./scripts/publish-to-github-packages.sh
#
# Usage (local - token written to a temporary settings.xml):
#   GITHUB_TOKEN=ghp_... GITHUB_ACTOR=myuser ./scripts/publish-to-github-packages.sh
#
# Optional:
#   VERSION           - release version; default read from pom.xml
#   GITHUB_REPOSITORY - owner/repo (default cpkb-bluezoo/jprotobuf)
#   MAVEN_USERNAME    - auth user if GITHUB_ACTOR is unset (local PAT flows)

set -euo pipefail

GITHUB_REPOSITORY="${GITHUB_REPOSITORY:-cpkb-bluezoo/jprotobuf}"
REGISTRY_URL="https://maven.pkg.github.com/${GITHUB_REPOSITORY}"

POM_VERSION=$(grep -m1 '<version>' pom.xml | sed -E 's/.*<version>(.*)<\/version>.*/\1/')
if [ -z "$POM_VERSION" ]; then
    echo "error: could not read <version> from pom.xml" >&2
    exit 1
fi

if [ -z "${VERSION:-}" ]; then
    VERSION="$POM_VERSION"
    echo "==> Auto-detected VERSION=$VERSION from pom.xml"
fi

if [ "$POM_VERSION" != "$VERSION" ]; then
    echo "error: version mismatch - requested VERSION=$VERSION, but pom.xml's <version> says $POM_VERSION" >&2
    exit 1
fi

BUILD_XML_VERSION=$(grep -m1 'name="release"' build.xml | sed -E 's/.*value="([^"]*)".*/\1/')
if [ -n "$BUILD_XML_VERSION" ] && [ "$BUILD_XML_VERSION" != "$VERSION" ]; then
    echo "error: version mismatch - VERSION=$VERSION, but build.xml 'release' says $BUILD_XML_VERSION" >&2
    exit 1
fi

if ! command -v mvn >/dev/null 2>&1; then
    echo "error: mvn not found (install Maven or use CI)" >&2
    exit 1
fi

MAIN_JAR="dist/jprotobuf-$VERSION.jar"
SOURCES_JAR="dist/jprotobuf-$VERSION-sources.jar"
JAVADOC_JAR="dist/jprotobuf-$VERSION-javadoc.jar"

if [ ! -f "$MAIN_JAR" ]; then
    echo "==> Building release artifacts (version $VERSION)"
    ant release release-sources release-javadoc -Drelease="$VERSION"
fi

for f in "$MAIN_JAR" "$SOURCES_JAR" "$JAVADOC_JAR"; do
    if [ ! -f "$f" ]; then
        echo "error: missing $f" >&2
        exit 1
    fi
done

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

MVN_SETTINGS=()
if [ -n "${GITHUB_TOKEN:-}" ]; then
    MAVEN_USER="${GITHUB_ACTOR:-${MAVEN_USERNAME:-}}"
    if [ -z "$MAVEN_USER" ]; then
        echo "error: set GITHUB_ACTOR or MAVEN_USERNAME with GITHUB_TOKEN" >&2
        exit 1
    fi
    cat > "$WORKDIR/settings.xml" <<EOF
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <servers>
    <server>
      <id>github</id>
      <username>${MAVEN_USER}</username>
      <password>${GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
EOF
    MVN_SETTINGS=( -s "$WORKDIR/settings.xml" )
fi

echo "==> Uploading to GitHub Packages ($REGISTRY_URL)"
mvn --batch-mode "${MVN_SETTINGS[@]}" deploy:deploy-file \
    -DrepositoryId=github \
    -Durl="$REGISTRY_URL" \
    -Dfile="$MAIN_JAR" \
    -DpomFile=pom.xml \
    -Dsources="$SOURCES_JAR" \
    -Djavadoc="$JAVADOC_JAR"

echo "==> Published org.bluezoo:jprotobuf:$VERSION to GitHub Packages"
