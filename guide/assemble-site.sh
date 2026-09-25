#!/usr/bin/env bash
# Assemble the published site from a built guide (mvn -Pguide -pl guide package).
#
#   guide/assemble-site.sh <site-dir>
#
# Run from the repository root. The guide IS the site: its landing page is the
# home page. The downloads sit beside it at the root. Two kinds of forwarding
# page keep old links working:
#
#   - every "old new" line in guide/redirects.txt (renamed cards and sections)
#   - every page under /guide/, where the guide was first published
#
# Each forwarding page redirects to the new address, keeping the #fragment.
set -euo pipefail

site="${1:?usage: guide/assemble-site.sh <site-dir>}"
built=guide/target/guide-site

mkdir -p "$site"
cp -R "$built/." "$site/"
cp llms.txt .nojekyll "$site/"
cp guide/target/guide.pdf "$site/guide.pdf"
cp guide/target/paperband-intro.pptx "$site/paperband-intro.pptx"

# stub <file-to-write> <target, relative to the directory of the file>
stub() {
  mkdir -p "$(dirname "$1")"
  printf '<!DOCTYPE html><meta charset="utf-8"><title>Moved</title>\n<link rel="canonical" href="%s">\n<meta http-equiv="refresh" content="0; url=%s">\n<script>location.replace("%s" + location.hash)</script>\n<p>This page has moved to <a href="%s">%s</a>.</p>\n' \
    "$2" "$2" "$2" "$2" "$2" > "$1"
}

# "../" once per directory in a site-relative path: the way back to the root.
up() { echo "$1" | sed -e 's|[^/]*/|../|g' -e 's|[^/]*$||'; }

# Renamed pages. A line naming a page that still exists is a mistake.
grep -v '^\s*\(#\|$\)' guide/redirects.txt | while read -r old new; do
  if [ -e "$site/$old" ]; then echo "redirects.txt: $old still exists" >&2; exit 1; fi
  if [ ! -e "$site/$new" ]; then echo "redirects.txt: $new does not exist" >&2; exit 1; fi
  stub "$site/$old" "$(up "$old")$new"
done

# The original /guide/ addresses, for every page including the forwarding
# pages above, so a /guide/ link to a renamed card makes two hops, not a 404.
(cd "$site" && find . -name '*.html' -not -path './guide/*') | while read -r page; do
  page="${page#./}"
  stub "$site/guide/$page" "../$(up "$page")$page"
done
