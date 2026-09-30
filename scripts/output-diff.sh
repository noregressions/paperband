#!/usr/bin/env bash
# Check that a change leaves what the repository's own books build to alone,
# or that it changes only what it meant to.
#
#   scripts/output-diff.sh save     # build, and keep the outputs as the baseline
#   scripts/output-diff.sh check    # build again, and compare with the baseline
#
# Run from the repository root: save before the change, check after it. The
# books are the guide and every example (mvn install -Pguide,examples). Each
# site is compared as HTML, file by file, and each PDF as its text, through
# pdftotext from poppler, since a PDF's bytes carry more than its content.
# Decks (.pptx) aren't compared.
#
# Extra Maven arguments go in MVN_ARGS, which defaults to -DskipTests:
#
#   MVN_ARGS="-o" scripts/output-diff.sh check    # offline, with the tests
#
# check exits 0 when every output matches, 1 when some differ (it lists them,
# and keeps both copies under .output-diff/ to read), and 2 when the
# build fails -- so a broken build can't pass for "nothing changed".
set -euo pipefail

mode="${1:?usage: scripts/output-diff.sh save|check}"
# Outside every target/: the build cleans them, and the baseline has to outlive it.
out=.output-diff

command -v pdftotext >/dev/null || { echo "output-diff: needs pdftotext (poppler)" >&2; exit 2; }

build() {
  # clean, so a page an earlier build wrote and this one doesn't isn't compared
  # shellcheck disable=SC2086  # MVN_ARGS is a list of arguments
  if ! mvn -q clean install -Pguide,examples ${MVN_ARGS--DskipTests}; then
    echo "output-diff: the build failed; nothing was compared" >&2
    exit 2
  fi
}

# snapshot <dir>: every book's sites and PDF text, one folder per book
snapshot() {
  rm -rf "$1"
  for target in guide/target examples/*/target; do
    [ -d "$target" ] || continue
    book="$1/${target%/target}"
    mkdir -p "$book"
    for site in "$target"/*/; do
      [ -f "$site/index.html" ] && cp -R "$site" "$book/$(basename "$site")"
    done
    for pdf in "$target"/*.pdf; do
      [ -f "$pdf" ] && pdftotext "$pdf" "$book/$(basename "$pdf" .pdf).txt"
    done
  done
}

case "$mode" in
  save)
    build
    snapshot "$out/baseline"
    echo "output-diff: baseline saved in $out/baseline"
    ;;
  check)
    [ -d "$out/baseline" ] || { echo "output-diff: no baseline; run save first" >&2; exit 2; }
    build
    snapshot "$out/now"
    if diff -rq "$out/baseline" "$out/now"; then
      echo "output-diff: every output matches the baseline"
    else
      echo "output-diff: the outputs above differ; compare them under $out/" >&2
      exit 1
    fi
    ;;
  *)
    echo "usage: scripts/output-diff.sh save|check" >&2
    exit 2
    ;;
esac
