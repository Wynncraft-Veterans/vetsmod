---
name: vetsmod Extras
description: /wv extras, the unlisted easter eggs — the daily sudoku in a Wynntils book: its board and drawing, timer, stored board, reopen rules, and how a result is sent and answered
type: project
---
# vetsmod Extras

`/wv extras` holds the easter eggs, each a subsystem of its own in a sub-package of [`org.wynnvets.extras`](../src/client/java/org/wynnvets/extras/). The tree is built by `ExtrasCommands`, nothing gates it, and like `/wv invite-force` it is never listed in `/wv help` ([vetsmod_commands.md](vetsmod_commands.md)).

## The daily sudoku

`/wv extras sudoku` opens one puzzle a day, the same for everyone, on the pages of a Wynntils book. Anyone can play, locked vetsmod included, and on or off Wynncraft. Only a player whose `/unlock` key the server has accepted sends a result, which the server ranks and posts. The client reports its own time and the stakes are low, so there is no minimum-time check.

| Piece | Where |
|---|---|
| Puzzle | temporary-server, `GET /v1/outbound/sudoku`, 503 until its first poll. The response is owned by its `v1_protocol.md` |
| Result | the `sudoku_submit` frame on `/v1/inbound`, through `V1ApiManager.sendStaffActionFrame` ([server_api_reference.md](server_api_reference.md)) |
| Command and open | [`SudokuCommand`](../src/client/java/org/wynnvets/extras/sudoku/SudokuCommand.java) |
| Fetch, send, reply, stored copy | [`SudokuClient`](../src/client/java/org/wynnvets/extras/sudoku/SudokuClient.java) |
| Rules, geometry, stored form, reopen choice | [`SudokuBoard`](../src/client/java/org/wynnvets/extras/sudoku/SudokuBoard.java), which touches no Minecraft or Wynntils class, so JUnit covers it |
| Drawing, input, timer | [`SudokuScreen`](../src/client/java/org/wynnvets/extras/sudoku/SudokuScreen.java), which cannot load under the harness |

### Opening

The open runs in this order, and only the first step is skipped when there is nothing to do:

1. A solved board still unsent goes out, if the session is authenticated, and the open waits for the reply.
2. The puzzle is fetched. A failure of any kind prints one red line and stops.
3. `TickDelays.runAfter` waits two ticks, as Wynntils' own command screens do, so the chat screen closing behind the command cannot overwrite the book. It is used rather than `Managers.TickScheduler` because the fetch's reply arrives off the client thread and `TickDelays` is safe from any thread, and closes the scheduler's traps.
4. On the client thread, the board opens only if `McUtils.screen()` is `null` or a `ChatScreen`; otherwise "Close this menu and run it again." `setScreen` over a server's container sends no close packet, which would leave the server and client out of step, and the check has to be here, after the wait.

### Board and drawing

The book is `Texture.CONTENT_BOOK_BACKGROUND`, 339×220, whose right page is plain from x=174 to x=328, with page-edge stripes from x=329. The board sits at (174, 32) and is 156 px square: 16 px items at a 17 px pitch, a 1 px line between cells, a 2 px line between boxes, a 1 px outer edge, the far one on the first stripe. A blank draws one glass pane with `renderFakeItem`. A digit's cell draws a dark backing, the pane twice, then the digit centred with `FontRenderer.renderText`. Wynncraft's glass is about half see-through: the backing keeps the white digits readable on the pale page, and the second pane keeps the colour from going muddy over it.

The glass follows the usual way sudoku apps colour cells, by what a cell is showing rather than by its digit. No nine-colour digit palette reads well for everyone, and white digits would be lost on some of those colours. `SudokuBoard.role` picks one, the first that applies:

| `CellRole` | Cell | Glass |
|---|---|---|
| `SELECTED` | The selected cell | Yellow |
| `CONFLICT` | A digit repeated in its row, column or box, on every copy | Red |
| `SAME_DIGIT` | A digit matching the selected cell's | Cyan |
| `GIVEN` | A digit that came with the puzzle | Gray |
| `ENTRY` | A digit the player set | Blue |
| `PEER` | A blank in the selected cell's row, column or box | White |
| `EMPTY` | Any other blank | Light gray |

The left page has `renderTitle`, then the difficulty, time and status, the rules and controls, and the wait until the next puzzle, with its readings in dark red and the rules and wait in dark gray, each wrapped by `FontRenderer.renderAlignedTextInBox` and stacked by `calculateRenderHeight`. The wait is worked out on the client: the next puzzle comes at 04:00 in `America/Halifax` on the day after the board's. The server dates its very first puzzle a day ahead so that it stays up through the next rollover, which this reads correctly. An earlier day's board has no difficulty line: the fetch only names the current puzzle's.

The bottom ribbon holds the puzzle's number, the days since the first puzzle's day (`SudokuBoard.FIRST_DAY`, 2026-11-02) plus one, then the player's streak, or "Unlinked" when `SendState` is `NEVER`. The streak comes from the last reply: a new board carries on the streak of the day before its own, or of the puzzle it replaced less that puzzle's result, and starts at 0 otherwise, so it can read one lower than the server's until today's result goes out.

Clicking selects a cell, a given included, so its matching digits light up; a grid line and a solved board do not. Setting a given does nothing. Number keys (both rows) set the selected cell, Backspace and Delete clear it, and every other key falls through to Wynntils and vanilla, so Esc closes the book. Control with a number key adds or removes a pencil mark on a blank. Not Shift, which Windows hides from the number pad while Num Lock is on. Marks draw at half size, each in its digit's place in a three-by-three grid, and stamp no time. A digit replaces the cell's marks, Backspace on a blank clears them, and a digit set elsewhere leaves other cells' marks alone. There are no arrow keys.

### Timer

Active time is real time while the screen is current, by `System.nanoTime`. It starts in `added()` and stops in `removed()`: `init()` runs again on a resize, and `ScreenClosedEvent` does not fire when a server menu replaces the screen. A solve freezes it. Setting a cell banks the time and stamps the cell with `max(1, ceil(activeMs / 1000))`, so a fast first entry never reads as 0, which is what marks a given. A board's time is its largest stamp, which is how the server works it out, and the client never sends it.

### Stored board

`sudokuProgress` holds `{day, cells, fills, activeMs, solved, submitted, rank, streak, notes}`, like `retroWynntilsSnapshot` a JSON string in the config rather than a file of its own. It is saved on every cell change and in `removed()`. A cell with a digit and no time is a given, so a stored board needs no copy of its puzzle, which also shows when staff have replaced the day's puzzle: the same day with other digits starts a fresh board. An open board of the replaced puzzle gets the server's refusal when it is solved. `notes` holds each blank's pencil marks as bits 1 to 9, and a board stored without it has none. Text that is not a board this class could have written is logged and treated as none.

| Event | What happens |
|---|---|
| Esc, restart, world change, or a server menu opens | The time is banked, and the same command resumes. Nothing reopens by itself |
| Rollover with the screen open | The board keeps its `day` and submits it; the server says if that day has closed |
| Reopen with a solved, unsent board and an authenticated session | It is sent first (Opening, step 1) |
| Reopen with today's board | It carries on, or shows finished with its time. |
| Reopen with an older unsent board that can still go out | It stays on screen, finished, until it goes out or the server closes it. There is one stored slot, and a new board would overwrite it |
| Reopen with any other older board | It is dropped and a new one starts |

"Can still go out" is `SendState` `READY` or `LATER` below. So a key holder off Wynncraft sees yesterday's finished board until their next Wynncraft join sends it.

### Solving

A full board that breaks the rules shows "Not solved yet" and stays editable. A valid one is solved, checked on the client, and what follows depends on `SudokuClient.sendState()`:

| `SendState` | When | What happens |
|---|---|---|
| `READY` | `isAuthenticatedThisSession()` | The frame goes out and the reply prints |
| `LATER` | A stored key the server has not rejected, with no session yet: the inbound socket is down, as it is off Wynncraft, or up with its auth ack awaited or failed for some other reason | Time, then "Results send next time." The board stays unsent |
| `NEVER` | No key, or an auth reply rejected it on a live socket (`isStoredKeyRejected()`) | Time, then a line pointing at `~vetsmod`. The board stays unsent, so a key gained the same day still sends it |

Only a verdict on the key makes it `NEVER`, because an older unsent board is dropped at `NEVER` (Stored board): a key still waiting on its ack must not lose a result.

The frame carries `day`, `grid` and `fills`; the server stamps the identity from the session. The reply is an untyped ack routed by its `req` and arrives off the client thread, so `SudokuClient.submit` hands it over with `McUtils.mc().execute`:

| Reply | Board | Chat |
|---|---|---|
| `status: ok`, `duplicate` or not | Finished | Green time, rank and streak |
| Error with `retry: false` | Finished, so it does not resend forever | Time, then the server's own `detail` in red, a fixed player-safe line |
| Error with `retry: true`, or a client-built error ack for a dead socket, a lost socket or no reply (`lostAck`, `timeoutAck`, none of which has `retry`) | Stays unsent | Time, then "Results send next time." |

The reply rewrites the stored copy only while it is still that day's, so a slow reply cannot put back a board the player has moved on from.
