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
| One player's board: cells and times, marks, hints, stored form, reopen choice | [`SudokuBoard`](../src/client/java/org/wynnvets/extras/sudoku/SudokuBoard.java) |
| Sudoku's rules over a grid, and its solver | [`SudokuGrid`](../src/client/java/org/wynnvets/extras/sudoku/SudokuGrid.java) |
| Drawing, geometry, input, timer | [`SudokuScreen`](../src/client/java/org/wynnvets/extras/sudoku/SudokuScreen.java) |

### Opening

The open runs in this order, and only the first step is skipped when there is nothing to do:

1. If the session is authenticated, any result still unsent goes out, the board kept aside first (Stored board), and the open waits for the replies.
2. The puzzle is fetched. A failure of any kind prints one red line and stops.
3. `TickDelays.runAfter` waits two ticks, as Wynntils' own command screens do, so the chat screen closing behind the command cannot overwrite the book. It is used rather than `Managers.TickScheduler` because the fetch's reply arrives off the client thread (see `TickDelays`).
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

The left page has `renderTitle`, then the difficulty and time, the rules, the controls or a status, and the wait until the next puzzle, each wrapped by `FontRenderer.renderAlignedTextInBox` and stacked by `calculateRenderHeight`. The wait is worked out on the client: the next puzzle comes at 04:00 in `America/Halifax` on the day after the board's. The server dates its very first puzzle a day ahead so that it stays up through the next rollover, which this reads correctly.

The bottom ribbon holds the puzzle's number, the days since the first puzzle's day (`SudokuBoard.FIRST_DAY`, 2026-11-02) plus one, then the player's streak, or "Unlinked" when `SendState` is `NEVER`. The streak comes from the last reply: a new board carries on the streak of the day before its own if the server ranked that day's result, since a day with none breaks it, or of the puzzle it replaced less that puzzle's result, and starts at 0 otherwise, so it can read one lower than the server's until today's result goes out. A board that keeps yesterday's solve aside takes that solve's streak once the server ranks it (`SudokuBoard.settleAside`).

Clicking selects a cell, a given included, so its matching digits light up; a grid line and a solved board do not. A drag that starts with a click on a cell selects every cell the pointer crosses, and several selected cells light up alone, without the row, column, box and digit that one lights up. The book takes these keys, and every other falls through to Wynntils and vanilla, so Esc closes it:

- **Numbers** (both rows) set the selected cell, and Backspace and Delete clear it; a given ignores both. A digit replaces its cell's pencil marks and takes itself out of the marks in its row, column and box, and Backspace on a blank clears its marks.
- **A number as a pencil mark**, with Control held, in pencil mode, or with more than one cell selected: it is added to every blank among them unless they all have it, when it comes off them all. Control rather than Shift, which Windows hides from the number pad while Num Lock is on. Marks stamp no time.
- **N** switches pencil mode, which is off whenever the book opens. While it is on the selection is orange rather than yellow and "Pencil mode on" shows above the board, so it is plain with nothing selected.
- **A** pencils every blank with the digits its row, column and box leave open, replacing its marks.
- **The arrow keys** move the selection one cell, stopping at the edge, and start from the centre.
- **H** is a hint, and adds 30 s to the time so a hinted solve can't outrank a clean one. It selects the first wrong entry, else the first blank only one digit can fill (its one open digit, or the only place for a digit in its row, column or box), else the first blank with fewest digits open, and its line says which of the three. The client works the solution out from the givens to spot a wrong entry, and never stores it.

The left page lists the controls one a line, each short enough not to wrap. A hint's line, until the next key or click, or the board's status takes their place below the rules, so the lines above stay put and the page doesn't run into the ribbon.

### Timer

Active time is real time while the screen is current, by `System.nanoTime`. It starts in `added()` and stops in `removed()`: `init()` runs again on a resize, and `ScreenClosedEvent` does not fire when a server menu replaces the screen. A solve freezes it. Setting a cell banks the time and stamps the cell with `max(1, ceil(activeMs / 1000))`, so a fast first entry never reads as 0, which is what marks a given. A board's time is its largest stamp, which is how the server works it out, and the client never sends it.

### Stored board

`sudokuProgress` holds `{day, cells, fills, activeMs, solved, submitted, rank, streak, notes, aside}`, `aside` being an earlier day's unsent solve in the same form, or absent, like `retroWynntilsSnapshot` a JSON string in the config rather than a file of its own. It is saved on every cell change and in `removed()`. A cell with a digit and no time is a given, so a stored board needs no copy of its puzzle, which also shows when staff have replaced the day's puzzle: the same day with other digits starts a fresh board. An open board of the replaced puzzle gets the server's refusal when it is solved. `notes` holds each blank's pencil marks as bits 1 to 9, and a board stored without it has none. Text that is not a board this class could have written is logged and treated as none.

| Event | What happens |
|---|---|
| Esc, restart, world change, or a server menu opens | The time is banked, and the same command resumes. Nothing reopens by itself |
| Rollover with the screen open | The board keeps its `day` and submits it; the server says if that day has closed |
| Reopen with a solved, unsent board, or one kept aside, and an authenticated session | It is sent first (Opening, step 1) |
| Reopen with today's board | It carries on, or shows finished with its time. |
| Reopen with an older unsent board that can still go out | It is kept aside in the new day's board, which opens, so it never holds up a puzzle. Once its day has closed the server refuses it with `retry: false`, which finishes it too. Whatever it had kept aside itself is dropped, its day long closed |
| Reopen with any other older board | It is dropped and a new one starts |

"Can still go out" is `SendState` `READY` or `LATER` below. Nothing sends a board by itself: only the open's first step does, so a key holder off Wynncraft keeps a result aside until they next run `/wv extras sudoku` with their session authenticated.

### Solving

A full board that breaks the rules shows "Not solved yet" and stays editable. A valid one is solved, checked on the client, and what follows depends on `SudokuClient.sendState()`:

| `SendState` | When | What happens |
|---|---|---|
| `READY` | `isAuthenticatedThisSession()` | The frame goes out and the reply prints |
| `LATER` | A stored key the server has not rejected, with no session yet: the inbound socket is down, as it is off Wynncraft, or up with its auth ack awaited or failed for some other reason | Time, then "Results send next time." The board stays unsent |
| `NEVER` | No key, or an auth reply rejected it on a live socket (`isStoredKeyRejected()`) | Time, then a line pointing at `~vetsmod`. The board stays unsent, so a key gained the same day still sends it |

Only a verdict on the key makes it `NEVER`, because an older unsent board is not kept aside at `NEVER` (Stored board): a key still waiting on its ack must not lose a result.

The frame carries `day`, `grid` and `fills`; the server stamps the identity from the session. The reply is an untyped ack routed by its `req` and arrives off the client thread, so `SudokuClient.submit` hands it over with `McUtils.mc().execute`:

| Reply | Board | Chat |
|---|---|---|
| `status: ok`, `duplicate` or not | Finished | Green time, rank and streak |
| Error with `retry: false` | Finished, so it does not resend forever | Time, then the server's own `detail` in red, a fixed player-safe line |
| Error with `retry: true`, or a client-built error ack for a dead socket, a lost socket or no reply (`lostAck`, `timeoutAck`, none of which has `retry`) | Stays unsent | Time, then "Results send next time." |

The reply rewrites the stored copy only while it is still that puzzle's, the same day with the same givens, so a slow reply cannot put back a board the player has moved on from. A reply to the board kept aside rewrites the board keeping it, which lets it go once it is finished.
