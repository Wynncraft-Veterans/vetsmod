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
# Run it by hand, and in every comment-reconciliation sweep.
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
# 5.5c checked its six packages against that rule and EXCLUDED two symbols
# for it: AnniModeManager.current has an in-class caller in transitionTo, and
# AnniModeManager.transitionTo has one in applyStartupDefaultIfNeeded. The
# script reports 3 and 5; the real totals are 4 and 6. Neither belongs here.
#
# SECOND BLIND SPOT, found by 5.5d: count_calls used to match a bare
# substring, so "DebugCommands.buildCommandTree(" also matched every
# "AnniDebugCommands.buildCommandTree(" and reported 2 where the truth is 1.
# The pattern now starts at a word boundary (\b), so a class name that is a
# suffix of another class name no longer inflates the count. A package-
# qualified call (org.wynnvets.debug.DebugCommands.buildCommandTree()) still
# counts -- the dot before the class name is a boundary. All 22 entries that
# predate the fix returned the same numbers after it.
#
# PATTERNS is a second, smaller manifest for counts no call-site grep can
# see: occurrences of a fixed string on NON-comment lines of ONE file. It
# exists for in-class call counts the header above rules out of MANIFEST,
# e.g. the 24 requireDebug(ctx) and 5 requireStaffOrOrganiser(ctx) sites in
# AnniDebugCommands, which its class doc states and 6d will inherit. Only
# use it for strings that never wrap across lines after spotlessApply: a
# method reference split onto a continuation line is invisible to it (the
# text AnniDebugCommands:: matches 16 of that class 24 handler references).
#
# NOTE: MANIFEST and PATTERNS are single-quoted, so a note field must not
# contain an apostrophe. It terminates the string and the script dies at the
# next paren.
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
AnniSnapshotCache.addListener|6|AnniSnapshotCache: "Six are registered today"
AnniSnapshotCache.update|5|AnniSnapshotCache: three classes across five call sites
AnniAggressiveTicker.isAggressiveActive|6|AnniAggressiveTicker: "Six call sites in all"
AnniWindows.inHotWindow|2|AnniWindows: AnniOutlineTicker + AnniAggressiveTicker
AnniWindows.hotWindowClosed|1|AnniWindows: the watcher needs the closing edge alone
AnniModeManager.preferredMode|2|AnniModeManager: "Consulted by exactly two callers"
AnniZone.isCold|1|AnniZone: one caller, the DebugCommands zone dump
DebugCommands.buildCommandTree|1|DebugCommands class doc: CommandRegistry is the single integration point
AnniDebugCommands.buildCommandTree|1|AnniDebugCommands#buildCommandTree: caller is DebugCommands
DebugConfigManager.isDebugConfigKey|2|DebugConfigManager#isDebugConfigKey: the two /wv debug set handlers
AnimatedGradientSequence.beginAnimation|1|vetsmod_rendering.md: the one real caller, ChatUtils.dispatchAnimatedChat
GuildStateManager.isStaffOfAnyGuild|1|vetsmod_distribute.md sec 1: each gate predicate has exactly one call site
GuildStateManager.isChiefOfAnyGuild|1|vetsmod_distribute.md sec 1; isChiefOfAnyGuild names ensureChief as its call site
V1ApiManager.confirmedStaffRank|1|V1ApiManager#confirmedStaffRank: One consumer, via the GuildStateManager delegate
OutboundCommand.queueFront|2|vetsmod_distribute.md sec 8: Two call sites, both in GuildManageOpener
GuildManageOpener.openGuildLog|1|GuildManageOpener#openGuildLog: Used by GraidsDistributor (names the class, pinned as one site)
GuildLogWalker.armWalk|1|GuildLogWalker#armWalk: The only caller today, GraidsDistributor
MembersListWalker.armWalk|1|MembersListWalker class doc: its one caller pairs armWalk with openManageMembers
NameResolver.fetchAllLegacyNames|1|NameResolver#fetchAllLegacyNames: Used by RandomDistributor (names the class, pinned as one site)
NameResolver.fetchUuidToLegacyName|1|NameResolver#fetchUuidToLegacyName: Used by NoAspectsFilter (names the class, pinned as one site)
GuildStateManager.setDebugForceGuildlessUnlocked|0|GuildStateManager#setDebugForceGuildlessUnlocked: Nothing in vetsmod calls this today (in-class half in PATTERNS)
GuildStateManager.isProcessingModGuildCheck|0|GuildStateManager#isProcessingModGuildCheck: Nothing calls it at present (in-class half in PATTERNS)
StaffRanksPoller.refreshNow|1|StaffRanksPoller#refreshNow: called on each successful inbound auth ack, the one V1ApiManager site
ChatUtils.encodePillIfAscii|0|ChatUtils#encodePillIfAscii: Within vetsmod only this class calls it
ChatUtils.sendGuildChatMessageRed|0|vetsmod_chat_pipeline.md sec 6: reached only through sendStaffChannelMessage (in-class half in PATTERNS)
QueueDetector.handleTitleText|1|QueueDetector#handleTitleText: the TitleSetTextEvent handler and QueueTitleMixin; the mixin is the qualified site (in-class half in PATTERNS)
RankDisplayMap.vTagFor|1|RankDisplayMap#vTagFor: the label of a /v staff-channel pill, read by ChatUtils.buildStaffPillComponent
'

# file (under $SRC)|fixed string|expected occurrences on non-comment lines|note
PATTERNS='
org/wynnvets/mwe/anni/debug/AnniDebugCommands.java|requireDebug(ctx)|24|AnniDebugCommands class doc: all 24 handlers open with requireDebug
org/wynnvets/mwe/anni/debug/AnniDebugCommands.java|requireStaffOrOrganiser(ctx)|5|AnniDebugCommands class doc: the five scrollspot handlers
org/wynnvets/guild/GuildStateManager.java|setDebugForceGuildlessUnlocked(|2|no-caller claim, in-class half: the declaration plus its UnlockManager delegate call
org/wynnvets/guild/GuildStateManager.java|isProcessingModGuildCheck(|2|no-caller claim, in-class half: the declaration plus its GuildChecker delegate call
org/wynnvets/chat/ChatUtils.java|sendGuildChatMessageRed(|2|one-caller claim, in-class half: the declaration plus the one sendStaffChannelMessage call
org/wynnvets/queue/QueueDetector.java|handleTitleText(|2|two-caller claim, in-class half: the declaration plus the TitleSetTextEvent handler call
'

# Count non-comment call sites of Class.method( across the client source set.
count_calls() {
  grep -rn --include=*.java -- "\b${1//./\.}(" "$SRC" 2>/dev/null \
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

while IFS='|' read -r file pat want note; do
  [ -z "${file:-}" ] && continue
  got=$(grep -vE '^ *(\*|//|/\*)' "$SRC/$file" | grep -oF -- "$pat" | wc -l | tr -d ' ')
  if [ "$got" = "$want" ]; then mark='   '; else mark='!! '; fail=1; fi
  printf '%s%-31s %8s %8s   %s\n' "$mark" "$pat" "$want" "$got" "$note"
done <<< "$PATTERNS"

echo
if [ "$fail" -eq 0 ]; then
  echo "All manifest counts match the tree."
else
  echo "MISMATCH -- a count above is stale. Fix the prose, or the manifest if the"
  echo "code legitimately changed. Both are one-line edits; leaving it is not."
fi
exit "$fail"
