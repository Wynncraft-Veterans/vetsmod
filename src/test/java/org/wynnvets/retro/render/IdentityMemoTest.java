package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Tests for {@link IdentityMemo} and {@link WeakIdentityMemo}, the drawing code's caches. */
class IdentityMemoTest {

    // Distinct objects with equal values, so a memo comparing by equals would show.
    private static final String KEY = new String("key");
    private static final String EQUAL_KEY = new String("key");

    @Test
    void aMemoWorksItsValueOutOncePerKeyByIdentity() {
        IdentityMemo<String, Integer> memo = new IdentityMemo<>();
        AtomicInteger makes = new AtomicInteger();

        assertEquals(1, memo.get(KEY, key -> makes.incrementAndGet()));
        assertEquals(1, memo.get(KEY, key -> makes.incrementAndGet()), "the same key");
        assertEquals(2, memo.get(EQUAL_KEY, key -> makes.incrementAndGet()), "an equal one");
        assertEquals(3, memo.get(KEY, key -> makes.incrementAndGet()), "only the last is kept");
    }

    @Test
    void aMemoRemembersANullKeyAndANullValue() {
        IdentityMemo<String, String> memo = new IdentityMemo<>();
        AtomicInteger makes = new AtomicInteger();

        assertEquals("made", memo.get(null, key -> "made"));
        assertEquals("made", memo.get(null, key -> "again"), "a null key is a key");
        assertNull(
                memo.get(
                        KEY,
                        key -> {
                            makes.incrementAndGet();
                            return null;
                        }));
        assertNull(
                memo.get(
                        KEY,
                        key -> {
                            makes.incrementAndGet();
                            return null;
                        }));
        assertEquals(1, makes.get(), "a null value is a value");
    }

    @Test
    void aWeakMemoWorksItsValueOutOncePerKeyAndPeeksWithoutWorking() {
        WeakIdentityMemo<String, String> memo = new WeakIdentityMemo<>();

        assertNull(memo.peek(KEY), "nothing yet");
        assertEquals("first", memo.get(KEY, key -> "first"));
        assertSame("first", memo.get(KEY, key -> "again"));
        assertEquals("first", memo.peek(KEY));
        assertNull(memo.peek(EQUAL_KEY), "by identity");
        assertNull(memo.peek(null));

        assertEquals("second", memo.get(EQUAL_KEY, key -> "second"));
        assertNull(memo.peek(KEY), "the old key is gone");
    }

    @Test
    void aWeakMemoRemembersANullValueAndClears() {
        WeakIdentityMemo<String, String> memo = new WeakIdentityMemo<>();

        assertNull(memo.get(KEY, key -> null));
        assertNull(memo.get(KEY, key -> "again"), "a null value is a value");

        memo.clear();
        assertEquals("made", memo.get(KEY, key -> "made"), "cleared");
    }
}
