#!/bin/sh
set -eu
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
python3 .woodpecker/check-release.py
# Pin shared tooling and the Kotlin client to an exact, reviewed SDK commit.
git clone https://github.com/NimbyRails-France/sdk.git .ci/sdk
git -C .ci/sdk checkout --detach "$(cat .woodpecker/sdk-revision.txt)"
export JAVA_HOME="$(python3 .ci/sdk/.woodpecker/toolchain.py java-linux)"
export PATH="$JAVA_HOME/bin:$PATH"
python3 .woodpecker/fetch-sdk.py
export NRF_KOTLIN_HOME="$(python3 .ci/sdk/.woodpecker/toolchain.py kotlin-linux)"
# SDK alpha.2 validates its intermediate descriptor against the legacy URL.
# package.py rewrites both public manifests to NRF before hashing/publication.
sh gradlew packageMod -PnrfSdkDir="$PWD/.ci/sdk-kit" -PnrfWineRunner="$PWD/.ci/sdk/.woodpecker/wine-run.py" -Pkotlin.native.home="$NRF_KOTLIN_HOME" -PreleaseBaseUrl="https://github.com/NimbyRails-France/${CI_REPO##*/}/releases/download/v$(cat VERSION)" --no-daemon --max-workers=2 --console=plain
python3 .woodpecker/package.py
