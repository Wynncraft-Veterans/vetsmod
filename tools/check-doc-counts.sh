#!/usr/bin/env bash
# Verify call-site counts asserted in Javadoc and in .claude/ prose.
#
# Why this exists: Phase 5.5a's dead-consumer sweep audited 149 prose
# references and found 23 defects. Five of the 23 were stale NUMBERS, and
# numbers are the one part of that sweep a machine can settle outright --
# extract the claimed count, grep the real one, compare. At the time it was
# written, util/Json.java's own Javadoc and util/JsonTest.java disagreed about
# Json's call-site count; this script would have caught that before it landed.
#
# It is deliberately NOT a build gate. Spelled-out numerals in prose are
# ambiguous enough to false-positive on correct text, and adding a third
# mechanical gate mid-plan is a change to Mellow Rain's standing rules.
# Run it by hand, and in the 5.5b-5.5f sweeps.
#
# KNOWN BLIND SPOT, found by 5.5b: count_calls greps for "Class.method(", so it
# sees QUALIFIED call sites only. A caller inside the declaring class calls the
# method unqualified and is invisible here. Worked example --
# AnniHoverBuilder.roleColor genuinely has two callers, AnniHoverBuilder.roleChip
# and AnniMotdRenderer.assignedToPartyLine, and this script reports 1, because
# roleChip is in-class. So: only add a symbol to the manifest when every caller
# is out-of-class, and read a low number as "possibly in-class callers" before
# reading it as "the prose is stale". Symbols with in-class callers have to be
# checked by hand.
#
#   tools/check-doc-counts.sh            # check the manifest
#   tools/check-doc-counts.sh --list     # find count claims a human should read
#   tools/check-doc-counts.sh --count 'Json.optString'
#
# Exit 1 if any manifest entry disagrees with the tree.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 2

SRC=src/client/java
# symbol|expected|note   -- expected is the live call-site count at HEAD.
MANIFEST='
Json.optString|17|Json.java class doc
Json.stringOrNull|17|Json.java: "seventeen once NameResolver s five joined"
Json.stringOrEmpty|11|Json.java: "eleven call sites"
ColorMath.interpolateRgb|4|vetsmod_rendering.md + ColorMath.java, stated 3x
ConfigValueText.booleanLine|5|ConfigValueTextTest
ConfigValueText.intLine|3|ConfigValueTextTest
ConfigValueText.stringLine|4|ConfigValueTextTest
ConfigValueText.triStateLine|2|ConfigValueTextTest
AnniOutlinePalette.chatFormattingForRole|2|AnniOutlinePalette + AnniHoverBuilder#roleColor: "Two callers"
AnniOutlineRegistry.clearAll|1|AnniOutlineRegistry: "its only caller is AnniDebugCommands#registryClearAll"
AnniOutlineTicker.isOutlineSuppressionActive|4|AnniOutlineTicker: "two behavioural readers" + "two debug dumps"
VetsBossBarManager.isActive|2|VetsBossBarManager: mixin gate + DebugCommands dump
VetsBossBarManager.barUuid|2|VetsBossBarManager: mixin filter + DebugCommands dump
FlashTracker.styleFor|4|VetsBossBarContentBuilder class doc: "four chips"
FlashTracker.reset|1|FlashTracker: "One caller: VetsBossBarManager deactivate"
'

# Count non-comment call sites of Class.method( across the client source set.
count_calls() {
  grep -rn --include=*.java -- "${1//./\.}(" "$SRC" 2>/dev/null \
    | grep -vE '^[^:]+:[0-9]+: *(\*|//|/\*)' \
    | grep -cv "static .*${1##*.}(" 
}

case "${1:-}" in
  --count)
    [ $# -eq 2 ] || { echo "usage: $0 --count 'Class.method'" >&2; exit 2; }
    echo "$(count_calls "$2")  $2"; exit 0 ;;
  --list)
    echo "Count claims in Javadoc and .claude/ prose -- read each against the tree:"
    echo
    grep -rnEi --include=*.java --include=*.md \
      '(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|[0-9]+)[ -]+(call sites?|callers?|consumers?|readers?|holders?|entry points?)' \
      "$SRC" src/test/java .claude 2>/dev/null \
      | sed 's/^/  /'
    exit 0 ;;
  -h|--help)
    sed -n '2,20p' "$0" | sed 's/^# \?//'; exit 0 ;;
esac

fail=0
printf '%-34s %8s %8s   %s\n' SYMBOL CLAIMED ACTUAL NOTE
printf '%-34s %8s %8s   %s\n' '------' '-------' '------' '----'
while IFS='|' read -r sym want note; do
  [ -z "${sym:-}" ] && continue
  got=$(count_calls "$sym")
  if [ "$got" = "$want" ]; then mark='   '; else mark='!! '; fail=1; fi
  printf '%s%-31s %8s %8s   %s\n' "$mark" "$sym" "$want" "$got" "$note"
done <<< "$MANIFEST"

echo
if [ "$fail" -eq 0 ]; then
  echo "All manifest counts match the tree."
else
  echo "MISMATCH -- a count above is stale. Fix the prose, or the manifest if the"
  echo "code legitimately changed. Both are one-line edits; leaving it is not."
fi
exit "$fail"
