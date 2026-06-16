package org.wynnvets.mwe.anni.state;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;

/**
 * Immutable Gson-deserialised mirror of the canonical anni snapshot dict.
 *
 * <p>Wire shape is owned by vets-anni's
 * {@code app/domain/snapshot.py::assemble_snapshot} and documented in
 * {@code vets-anni/.claude/snapshot_integration.md}. Field names match the
 * JSON keys verbatim (snake_case) so Gson can hydrate without a custom
 * deserialiser — same convention as {@link org.wynnvets.datamodels.MembershipSnapshot}.</p>
 *
 * <p>Nullable references reflect the wire contract: a sub-object is
 * absent when the underlying state doesn't apply (e.g. {@link #event} is
 * null until an {@code AnniEvent} row exists in the vets-anni DB). Callers
 * MUST null-check at every nested access; accessor methods use the
 * Boolean.TRUE.equals pattern for primitive-Boolean fields so they
 * behave the same as Java {@code boolean} but tolerate JSON nulls.</p>
 *
 * <p>The class is hand-written rather than Lombok/record because vetsmod
 * uses plain Java beans for Gson-shaped data (see
 * {@link org.wynnvets.datamodels.MembershipSnapshot}); records would
 * require a custom {@code TypeAdapter} for snake_case wire fields.</p>
 */
public final class AnniSnapshot {

    private static final Gson GSON = new Gson();

    private Integer schema_version;
    private String mc_uuid;
    private String mc_username;
    private Event event;
    private Registration registration;
    private Rsvp rsvp;
    private Board board;
    private Attendance attendance;
    private List<String> organisers;

    public int schemaVersion() {
        return schema_version != null ? schema_version : 0;
    }

    public String mcUuid() {
        return mc_uuid;
    }

    public String mcUsername() {
        return mc_username;
    }

    public Event event() {
        return event;
    }

    public Registration registration() {
        return registration;
    }

    public Rsvp rsvp() {
        return rsvp;
    }

    public Board board() {
        return board;
    }

    public Attendance attendance() {
        return attendance;
    }

    public List<String> organisers() {
        return organisers != null ? organisers : Collections.emptyList();
    }

    /** Hydrate from the JSON shape produced by vets-anni. */
    public static AnniSnapshot fromJson(JsonObject json) {
        return GSON.fromJson(json, AnniSnapshot.class);
    }

    public static final class Event {
        private Long stamp_epoch;
        private Boolean announced;
        private Prediction prediction;

        public Long stampEpoch() {
            return stamp_epoch;
        }

        public boolean announced() {
            return Boolean.TRUE.equals(announced);
        }

        public Prediction prediction() {
            return prediction;
        }
    }

    public static final class Prediction {
        private Long earliest_epoch;
        private Long median_epoch;
        private Long latest_epoch;
        private Double window_hours;

        public Long earliestEpoch() {
            return earliest_epoch;
        }

        public Long medianEpoch() {
            return median_epoch;
        }

        public Long latestEpoch() {
            return latest_epoch;
        }

        public double windowHours() {
            return window_hours != null ? window_hours : 0.0;
        }
    }

    public static final class Registration {
        private Boolean registered;
        private Boolean core;
        private List<RoleEntry> roles;

        public boolean registered() {
            return Boolean.TRUE.equals(registered);
        }

        public boolean core() {
            return Boolean.TRUE.equals(core);
        }

        public List<RoleEntry> roles() {
            return roles != null ? roles : Collections.emptyList();
        }
    }

    public static final class RoleEntry {
        private String role;
        private String title;
        private String url;

        public String role() {
            return role;
        }

        public String title() {
            return title;
        }

        public String url() {
            return url;
        }
    }

    public static final class Rsvp {
        private String notice;
        private Long updated_at;
        private Boolean revoked;

        public String notice() {
            return notice;
        }

        public Long updatedAt() {
            return updated_at;
        }

        public boolean revoked() {
            return Boolean.TRUE.equals(revoked);
        }
    }

    public static final class Board {
        private String state;
        private Party party;
        private String role;
        private String wont_reason;

        public String state() {
            return state;
        }

        public Party party() {
            return party;
        }

        public String role() {
            return role;
        }

        public String wontReason() {
            return wont_reason;
        }
    }

    public static final class Party {
        private Integer ordinal;
        private String world;
        private String result;
        private PlayerRef host;
        private List<MemberRef> members;

        public int ordinal() {
            return ordinal != null ? ordinal : 0;
        }

        public String world() {
            return world;
        }

        public String result() {
            return result;
        }

        public PlayerRef host() {
            return host;
        }

        public List<MemberRef> members() {
            return members != null ? members : Collections.emptyList();
        }
    }

    public static final class PlayerRef {
        private String uuid;
        private String username;

        public String uuid() {
            return uuid;
        }

        public String username() {
            return username;
        }
    }

    public static final class MemberRef {
        private String uuid;
        private String username;
        private String role;

        public String uuid() {
            return uuid;
        }

        public String username() {
            return username;
        }

        public String role() {
            return role;
        }
    }

    public static final class Attendance {
        private Integer band;
        private String label;
        private String notice_effective;

        public int band() {
            return band != null ? band : 0;
        }

        public String label() {
            return label;
        }

        public String noticeEffective() {
            return notice_effective;
        }
    }
}
