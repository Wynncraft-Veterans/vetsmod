package org.wynnvets.items;

import com.wynntils.core.components.Models;
import com.wynntils.models.gear.type.GearInfo;
import com.wynntils.models.items.items.game.GearItem;
import com.wynntils.models.wynnitem.parsing.WynnItemParseResult;
import com.wynntils.models.wynnitem.parsing.WynnItemParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.logging.VetsLogger;

/**
 * Decides whether a gear item is underrolled: rerolled fewer times than {@code definitions.yml}'s
 * {@code min_rerolls} says any current copy is ({@link ItemDefinitions#minRerolls}). Such a copy
 * predates the change that set the minimum, which makes it legacy. An unidentified copy counts as
 * 0 rerolls, since identifying it would give 0.
 *
 * <h2>Where the count comes from</h2>
 * <p>Wynncraft draws the reroll counter as its own lore line on page 1 only, between the Combat
 * Level divider and the identifications, and draws no line at all at 0 rerolls. The line is a
 * gold {@code banner/box} chip ({@code U+E060}, a boxed digit {@code U+E050}+n per digit,
 * {@code U+E062}), the same digits in black at {@code U+E020}+n, and the {@code tooltip/banner}
 * reroll badge ending in {@code U+F005}. Wynntils' {@code WynnItemParser} decodes it from every
 * line, so the count is taken from {@link WynnItemParser#parseItemStack} on the current stack.</p>
 *
 * <p>Wynntils' own {@code GearItem.getRerollCount()} is not used: its {@code GearInstance} is
 * parsed once, when the item is annotated, and a page flip keeps the same {@code GearItem}, so
 * an item first annotated on page 2 or 3 reads 0 for good. The parse result's
 * {@code currentPage()} reads 0 while F3+H is on, so page 1 is told by content instead: a parse
 * that found the counter or any identification. Wynntils matches identifications only while
 * its page reads 0, and F3+H only ever forces that page to 0, so pages 2 and 3 never pass. An
 * item never seen on page 1 stays {@link Verdict#UNKNOWN}, which is never flagged.</p>
 *
 * <h2>Caching</h2>
 * <p>Two identity-keyed {@link WeakHashMap}s ({@link ItemStack} and Wynntils' {@link GearItem}
 * keep {@code Object}'s {@code equals}): one verdict per stack, so the slot highlight costs one
 * lookup per frame, and one entry per {@code GearItem} holding the count page 1 showed, so it
 * carries to pages 2 and 3. Wynntils' own per-item data store cannot hold this: Wynntils clears
 * it on every flip. No value references its key, so nothing leaks.</p>
 *
 * <p>Wynntils hands the old {@code GearItem} to any same-named stack in the slot whose page
 * differs, without comparing lore ({@code ItemHandler.isLoreSoftMatching}): turn a trade-market
 * results page while a listing shows page 2, and the next listing inherits it. So each entry
 * also keeps the item's {@link #header}, and an entry whose header no longer matches is
 * dropped.</p>
 *
 * <p>No unit test covers this class: it needs Wynntils, which is {@code modCompileOnly} and
 * absent at test runtime.</p>
 */
final class UnderrollDetector {

    /** What the detector decided for one stack. */
    enum Verdict {
        ENOUGH,
        UNDERROLLED,
        /** Not decidable; never flagged. */
        UNKNOWN
    }

    /** The source of a count read from the stack itself, which is then showing page 1. */
    private static final String STATS_PAGE = "stats page";

    /** Per stack: the verdict, so a slot costs one lookup per frame. */
    private static final Map<ItemStack, Result> STACK_RESULTS = new WeakHashMap<>();

    /** Per Wynntils item: the reroll count page 1 showed, across page flips. */
    private static final Map<GearItem, ItemEntry> ITEM_ENTRIES = new WeakHashMap<>();

    /** Set when a Wynntils API vetsmod calls is missing; the detector stays off afterwards. */
    private static boolean disabled = false;

    /**
     * The result for a stack Wynntils has not annotated as gear. Never cached: Wynntils may
     * still annotate the stack, and the annotation is a field read, so asking again is cheap.
     */
    private static final Result NOT_GEAR = skipped("not gear");

    /**
     * What {@link #evaluate} decided for one stack.
     *
     * @param source where the verdict came from: "stats page", "remembered", "unidentified",
     *     or why it was skipped
     * @param rerolls the reroll count, or 0 when none was read
     * @param min the item's {@code min_rerolls} threshold, or 0 when skipped before it was read
     */
    record Result(Verdict verdict, String source, int rerolls, int min) {
        /** Whether the count was read from this stack, which is then showing page 1. */
        boolean fromStatsPage() {
            return STATS_PAGE.equals(source);
        }
    }

    /** What the detector knows about one Wynntils item. */
    private static final class ItemEntry {
        /** The {@link #header} this entry was built from. */
        final String header;

        /** The reroll count page 1 showed, or {@code null} until page 1 has been seen. */
        Integer rerolls;

        ItemEntry(String header) {
            this.header = header;
        }
    }

    private UnderrollDetector() {}

    /**
     * Returns {@code true} if {@code stack} is an underrolled item, unless
     * {@code legacyItemUnderrolling} is off.
     */
    static boolean isUnderrolled(ItemStack stack) {
        if (!VetsConfig.get(VetsConfigKeys.LEGACY_ITEM_UNDERROLLING)) return false;
        return evaluate(stack).verdict() == Verdict.UNDERROLLED;
    }

    /** Evaluates {@code stack}, from the per-stack cache when it has been seen before. */
    static Result evaluate(ItemStack stack) {
        if (disabled || stack == null || stack.isEmpty()) {
            return skipped(disabled ? "disabled" : "no item");
        }
        Result cached = STACK_RESULTS.get(stack);
        if (cached != null) return cached;

        Result result;
        try {
            result = compute(stack);
        } catch (RuntimeException | LinkageError e) {
            if (e instanceof LinkageError) disabled = true;
            VetsLogger.debug(
                    "UnderrollDetector: could not evaluate '{}': {}",
                    stack.getHoverName().getString(),
                    e);
            result = skipped("error");
        }
        if (result != NOT_GEAR) STACK_RESULTS.put(stack, result);
        return result;
    }

    /** One line for the item dump: the verdict, its source, the count and the threshold. */
    static String summary(ItemStack stack) {
        Result result = evaluate(stack);
        StringBuilder text =
                new StringBuilder(result.verdict().name()).append(" (").append(result.source());
        if (result.min() > 0) {
            text.append(", rerolls ").append(result.rerolls()).append(", min ");
            text.append(result.min());
        }
        return text.append(')').toString();
    }

    private static Result compute(ItemStack stack) {
        int min = ItemDefinitions.minRerolls(LegacyItemHandler.normalizedHoverName(stack));
        if (min <= 0) return skipped("not listed");

        Optional<GearItem> gear = Models.Item.asWynnItem(stack, GearItem.class);
        if (gear.isEmpty()) return NOT_GEAR;
        GearItem item = gear.get();
        if (item.isUnidentified()) return decide("unidentified", 0, min);

        GearInfo info = item.getItemInfo();
        List<Component> lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines();
        String header = header(lore);
        ItemEntry entry = ITEM_ENTRIES.get(item);
        if (entry == null || !entry.header.equals(header)) {
            if (entry != null) {
                VetsLogger.debug(
                        "UnderrollDetector: '{}' changed header; dropping its entry", info.name());
            }
            entry = new ItemEntry(header);
            ITEM_ENTRIES.put(item, entry);
        }

        WynnItemParseResult parsed =
                WynnItemParser.parseItemStack(stack, info.getVariableStatsMap());
        if (parsed.rerolls() > 0 || !parsed.identifications().isEmpty()) {
            entry.rerolls = parsed.rerolls();
            return decide(STATS_PAGE, entry.rerolls, min);
        }
        if (entry.rerolls == null) return undecided("stats page not seen", min);
        return decide("remembered", entry.rerolls, min);
    }

    /** UNDERROLLED or ENOUGH, from a count read (or taken as 0) from {@code source}. */
    private static Result decide(String source, int rerolls, int min) {
        Verdict verdict = rerolls < min ? Verdict.UNDERROLLED : Verdict.ENOUGH;
        return new Result(verdict, source, rerolls, min);
    }

    /**
     * The item's header: the text of its lore above the first divider (name, banners, any
     * trade-market price, health or damage, defences). Every page of one item shows it alike,
     * and a different item shows another.
     */
    private static String header(List<Component> lore) {
        StringBuilder text = new StringBuilder();
        for (Component line : lore) {
            if (NewFormatRenderer.containsFont(line, NewFormatRenderer.DIVIDER_FONT)) break;
            text.append(line.getString()).append('\n');
        }
        return text.toString();
    }

    /** UNKNOWN for a listed item whose count is not known yet. */
    private static Result undecided(String why, int min) {
        return new Result(Verdict.UNKNOWN, why, 0, min);
    }

    /** UNKNOWN for an item skipped before its threshold was read. */
    private static Result skipped(String why) {
        return undecided(why, 0);
    }
}
