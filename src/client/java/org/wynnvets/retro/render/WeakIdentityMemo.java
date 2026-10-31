package org.wynnvets.retro.render;

import java.lang.ref.WeakReference;
import java.util.function.Function;

/**
 * {@link IdentityMemo} for a key that must not outlive its owner: a screen, which a cache must not
 * keep alive once it has closed. A {@code null} value is remembered like any other. Not
 * thread-safe; the render thread's.
 *
 * @param <K> the key
 * @param <V> the value
 */
final class WeakIdentityMemo<K, V> {

    private WeakReference<K> key = new WeakReference<>(null);
    private V value;

    /**
     * The value for {@code key}.
     *
     * @param key the key, not {@code null}
     * @param make works the value out, when {@code key} is not the key it holds
     * @return the value
     */
    V get(K key, Function<? super K, ? extends V> make) {
        if (this.key.get() != key) {
            value = make.apply(key);
            this.key = new WeakReference<>(key);
        }
        return value;
    }

    /**
     * The value for {@code key} if it has one already, working nothing out.
     *
     * @param key the key, or {@code null}
     * @return the value; {@code null} for any other key, and for {@code null}
     */
    V peek(K key) {
        return key != null && this.key.get() == key ? value : null;
    }

    /** Forgets the key and the value. */
    void clear() {
        key = new WeakReference<>(null);
        value = null;
    }
}
