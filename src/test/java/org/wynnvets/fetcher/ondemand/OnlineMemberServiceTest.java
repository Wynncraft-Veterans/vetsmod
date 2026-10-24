package org.wynnvets.fetcher.ondemand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OnlineMemberService#overlayRoster}, the step of the online-member merge that
 * lays the server's polled roster (UUID to current username) over the Wynntils names, and for
 * {@link OnlineMemberService#trustedTabEntries}, the gate on the tab list's guild column. The rest
 * of {@code merge()} is not covered: it reads Wynntils models and the tab list.
 */
class OnlineMemberServiceTest {

    private static final String DASHED = "bb171c68-7080-4d02-be7b-cc2d5dcbcd82";
    private static final String UNDASHED_UPPER = "BB171C6870804D02BE7BCC2D5DCBCD82";

    private final Map<String, String> uuidToUsername = new LinkedHashMap<>();
    private final Map<String, String> usernameToUuid = new LinkedHashMap<>();

    private void wynntilsKnows(String uuid, String name) {
        uuidToUsername.put(uuid, name);
        usernameToUuid.put(name.toLowerCase(Locale.ROOT), uuid);
    }

    @Test
    void overlayRoster_sameSpelling_replacesWynntilsName() {
        wynntilsKnows(DASHED, "OldName");

        OnlineMemberService.overlayRoster(
                uuidToUsername, usernameToUuid, Map.of(DASHED, "NewName"));

        assertEquals(Map.of(DASHED, "NewName"), uuidToUsername);
        assertEquals(DASHED, usernameToUuid.get("newname"));
    }

    @Test
    void overlayRoster_undashedUppercaseKey_replacesInsteadOfAdding() {
        wynntilsKnows(DASHED, "OldName");

        OnlineMemberService.overlayRoster(
                uuidToUsername, usernameToUuid, Map.of(UNDASHED_UPPER, "NewName"));

        assertEquals(Map.of(DASHED, "NewName"), uuidToUsername);
        assertEquals(DASHED, usernameToUuid.get("newname"));
    }

    @Test
    void overlayRoster_unparseableKey_keptRaw() {
        OnlineMemberService.overlayRoster(
                uuidToUsername, usernameToUuid, Map.of("not-a-uuid", "Somebody"));

        assertEquals(Map.of("not-a-uuid", "Somebody"), uuidToUsername);
        assertEquals("not-a-uuid", usernameToUuid.get("somebody"));
    }

    @Test
    void overlayRoster_nullOrBlankValue_skipped() {
        wynntilsKnows(DASHED, "OldName");
        Map<String, String> roster = new LinkedHashMap<>();
        roster.put(DASHED, null);
        roster.put("22222222-2222-2222-2222-222222222222", "  ");

        OnlineMemberService.overlayRoster(uuidToUsername, usernameToUuid, roster);

        assertEquals(Map.of(DASHED, "OldName"), uuidToUsername);
        assertEquals(1, usernameToUuid.size());
    }

    @Test
    void overlayRoster_emptyRoster_noChange() {
        wynntilsKnows(DASHED, "OldName");

        OnlineMemberService.overlayRoster(uuidToUsername, usernameToUuid, Map.of());

        assertEquals(Map.of(DASHED, "OldName"), uuidToUsername);
        assertEquals(Map.of("oldname", DASHED), usernameToUuid);
    }

    @Test
    void trustedTabEntries_notReturners_emptyAndNeverParsed() {
        List<String> entries =
                OnlineMemberService.trustedTabEntries(
                        false,
                        () -> {
                            fail("the tab list must not be parsed for a non-Returners player");
                            return List.of("OtherGuildMate");
                        });

        assertEquals(List.of(), entries);
    }

    @Test
    void trustedTabEntries_returners_passesParsedListThrough() {
        List<String> entries =
                OnlineMemberService.trustedTabEntries(true, () -> List.of("Alpha", "Bravo"));

        assertEquals(List.of("Alpha", "Bravo"), entries);
    }
}
