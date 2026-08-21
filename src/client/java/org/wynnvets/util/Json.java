package org.wynnvets.util;

import com.google.gson.Gson;

/**
 * The mod's shared {@link Gson}.
 *
 * <p>Twenty-one classes each declared their own {@code private static final Gson GSON = new
 * Gson()} &mdash; character-identical, unconfigured, and per-class in nothing but where the
 * copy happened to land. They all read this field instead.</p>
 *
 * <p>Sharing one instance is safe by Gson's own contract: {@code Gson} is documented as
 * thread-safe and intended to be reused across an application. What sharing it actually
 * shares is its internal type-adapter caching, and that is a win rather than a risk &mdash;
 * the classes deserialising the same wapi and vets-API payload shapes now warm one cache
 * between them instead of one apiece.</p>
 *
 * <p><b>The three configured instances are deliberately not residents.</b> Each builds through
 * a {@code GsonBuilder} and each depends on what it configured:
 * {@link org.wynnvets.config.VetsConfig VetsConfig} ({@code setPrettyPrinting}),
 * {@link org.wynnvets.debug.dump.ItemDumpHandler ItemDumpHandler}
 * ({@code + disableHtmlEscaping}) and
 * {@link org.wynnvets.mwe.anni.debug.AnniDebugCommands AnniDebugCommands}
 * ({@code + serializeNulls}, load-bearing for the anni debug round-trip and documented at its
 * own declaration). Folding any of them in here would change what it writes.</p>
 *
 * <p>This class holds JSON policy and nothing else. In particular it constructs no
 * {@code HttpClient}, so a JSON-only code path never drags a transport construction into its
 * {@code <clinit>}.</p>
 */
public final class Json {

    /**
     * The shared parser/serialiser. {@code new Gson()} with no builder, so HTML-escaping and
     * null-suppression are both at their defaults, exactly as all twenty-one copies had them.
     */
    public static final Gson GSON = new Gson();

    private Json() {}
}
