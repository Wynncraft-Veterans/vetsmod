package org.wynnvets.api;

/**
 * The wire-level reply state of the inbound socket's auth exchange: whether an auth frame is
 * awaiting its ack, which only an ack read as the auth reply clears, and what the last ok auth
 * ack said about staff, which an auth failure or a disconnect clears. Code outside this package
 * reads the staff half through {@link V1ApiManager#isConfirmedStaff()} and
 * {@link V1ApiManager#confirmedStaffRank()}.
 *
 * <p>The client side of the session (the tier, and whether this session has verified) is
 * {@link org.wynnvets.guild.UnlockManager UnlockManager}'s, and the key is persisted in
 * {@link org.wynnvets.config.VetsConfig VetsConfig}; neither is here.
 * {@link org.wynnvets.guild.SessionAuthWarning SessionAuthWarning}, despite its name, is the
 * once-per-session chat warning about that client-side state, not part of this one.</p>
 */
final class SessionAuthState {

    private SessionAuthState() {}

    /** Whether an auth frame is still awaiting its ack. Set whenever we send
     *  one; cleared when an ack is read as the auth reply. Auth-success acks are
     *  recognised by their {@code tier} key alone, so this flag matters only for
     *  acks that are neither ok nor shaped like a staff-action ack: such an ack that arrives while
     *  it is set is treated as an auth failure and is kept away from the pending staff-action
     *  callbacks in {@link V1ApiManager#pendingAcks} (an error whose detail starts with an
     *  auth-failure prefix counts as one whatever the flag says, unless the flag is clear, a
     *  staff-action callback is pending and the server does not echo request ids: that callback
     *  takes it). A staff-action-shaped error without a request id goes to the eldest callback,
     *  or is dropped when none is pending, whatever the flag says (if the client believed the
     *  server echoes ids, such an ack proves it does not). None of this applies to an ack that
     *  echoes a request
     *  id, which goes to its own callback. A lost inbound socket clears it, since its ack cannot
     *  come ({@link V1ApiManager#onInboundSocketLost}); {@link V1ApiManager#disconnect()} does
     *  not. */
    static volatile boolean expectingAuthAck = false;

    /** Server-confirmed staff status from the most recent successful auth.
     *  Rewritten by every ok auth ack; cleared by an auth failure and by
     *  {@link V1ApiManager#disconnect()}. The value is "modern verification token +
     *  WAPI-confirmed staff" -- not the local /gu rank cache. Read through
     *  {@link V1ApiManager#isConfirmedStaff()} (directly, or via
     *  {@link org.wynnvets.guild.GuildStateManager#isConfirmedStaff()
     *  GuildStateManager#isConfirmedStaff()}) by the staff-only commands and by
     *  the staff-chat eligibility gate in
     *  {@link org.wynnvets.chat.dispatcher.DispatchCoordinator DispatchCoordinator}. */
    static volatile boolean confirmedStaff = false;

    /** In-game guild rank from the staff roster ("strategist"/"chief"/
     *  "owner"), populated alongside {@link #confirmedStaff}. Empty when
     *  not staff. Captain was retired in the 2026-07 permission
     *  restructure; stray captains never reach {@code is_staff=true}. */
    static volatile String confirmedStaffRank = "";
}
