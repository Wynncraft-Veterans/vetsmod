package org.wynnvets.retro.render;

import java.util.function.Function;

/**
 * One value worked out from one key, kept until a different key by identity asks: the caches of the
 * drawing code, rebuilt with each generation's report or each screen's decision. It holds its key
 * strongly, so an old key lives until the next one replaces it. A {@code null} key and a
 * {@code null} value are remembered like any other. Not thread-safe; the render thread's.
 *
 * @param <K> the key
 * @param <V> the value
 */
final class IdentityMemo<K, V> {

    private K key;
    private V value;
    private boolean filled;

    /**
     * The value for {@code key}.
     *
     * @param key the key
     * @param make works the value out, when {@code key} is not the key it holds
     * @return the value
     */
    V get(K key, Function<? super K, ? extends V> make) {
        if (!filled || this.key != key) {
            value = make.apply(key);
            this.key = key;
            filled = true;
        }
        return value;
    }
}
