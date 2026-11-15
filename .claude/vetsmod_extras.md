---
name: vetsmod Extras
description: /wv extras, the unlisted easter eggs — the daily sudoku in a Wynntils book (its board and drawing, timer, stored board, reopen rules, and how a result is sent and answered) and the Vetlas main-menu panorama (its gate, download, loading and failure rules)
type: project
---
# vetsmod Extras

`/wv extras` holds the easter eggs, each a subsystem of its own in a sub-package of [`org.wynnvets.extras`](../src/client/java/org/wynnvets/extras/). The tree is built by `ExtrasCommands`, nothing gates it, and like `/wv invite-force` it is never listed in `/wv help` ([vetsmod_commands.md](vetsmod_commands.md)). There are two: the daily sudoku (`extras.sudoku`) and the Vetlas panorama behind the main menu (`extras.panorama`).

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

## The panorama

`/wv extras panorama on` swaps the panorama drawn behind the main menu for Vetlas, six faces vetsmod hosts on i.wynnvets.org and downloads when the egg is turned on (or the first time it would be drawn), while retro is off. Anyone can use it, on or off Wynncraft. Until the faces are downloaded, and if the download fails, the panorama is the one the packs give, which it is otherwise too: Wynncraft's Detlas with Wynntils' preloaded copy of its pack, else vanilla's. Retro draws its own panorama through its pack ([vetsmod_retro.md](vetsmod_retro.md) §6), so while retro is on the egg gives way. The gate is retro's setting, not its pack: with retro on and its `screens` part off, retro stopped for the session, or no Wynntils preload at the main menu, Old Detlas does not show either, and the packs' panorama does.

| Piece | Where |
|---|---|
| Command | [`PanoramaCommand`](../src/client/java/org/wynnvets/extras/panorama/PanoramaCommand.java) |
| Setting, gate, loading, releasing, failure | [`ExtrasPanorama`](../src/client/java/org/wynnvets/extras/panorama/ExtrasPanorama.java) |
| The draw | [`PanoramaRendererMixin`](../src/client/java/org/wynnvets/mixin/client/extras/PanoramaRendererMixin.java) ([vetsmod_mixins.md](vetsmod_mixins.md)) |
| Faces: pins and download | [`HostedPanorama.VETLAS`](../src/client/java/org/wynnvets/rendering/panorama/HostedPanorama.java), [`PanoramaDownloads`](../src/client/java/org/wynnvets/rendering/panorama/PanoramaDownloads.java) |
| The texture | [`HostedCubeMapTexture`](../src/client/java/org/wynnvets/rendering/panorama/HostedCubeMapTexture.java) |

### Command and setting

`/wv extras panorama` is a literal with `on` and `off` under it. Every node runs when typed bare, so none goes to the server, and nothing gates them. Typed bare it only says the current state; `on` and `off` store it and say the same thing. The reply is one line, `PanoramaCommand.line(on, retro, failed, download)`, the first row that applies (`download` is `PanoramaDownloads.state(HostedPanorama.VETLAS)`: `HERE`, `DOWNLOADING`, `FAILED` or `MISSING`), red when Vetlas failed to draw, yellow when its download, or reading its kept faces, failed, and green otherwise, as `/wv retro` colours its own:

| State | Reply |
|---|---|
| Off | "The Vetlas panorama is off." |
| On, Vetlas failed to draw this session | "The Vetlas panorama is on, but could not be drawn: it returns after a restart." |
| On, the download failed, or the kept faces could not be read | "The Vetlas panorama is on, but could not be downloaded or read: the next start-up tries again." |
| On, retro on | "The Vetlas panorama is on, and shows once retro is off." |
| On, retro off, faces not here yet (downloading or missing) | "The Vetlas panorama is on: it shows behind the main menu once it is downloaded." |
| On, retro off, faces here | "The Vetlas panorama is on: it shows behind the main menu." |

So a failure to draw outranks a failed download, which outranks retro, which outranks a download still running. An `on` that starts the download answers while it runs, so it says "once it is downloaded".

The setting is the internal boolean `extrasPanorama` (`VetsConfigKeys.EXTRAS_PANORAMA`), default `false` in `VetsConfigDefaults.seed`, written only by `ExtrasPanorama.setOn`, which only the command calls. Turning it on also starts the download when the faces are `MISSING`, whatever retro is. It is not in `VetsConfigSchema.USER_CONFIG_KEYS`, so `/wv config` never lists it ([vetsmod_config.md](vetsmod_config.md) §1). It is read at every draw, so turning it on or off needs no reload, and a change shows from the next frame.

### The draw

`PanoramaRenderer.render(GuiGraphics, int, int, boolean)` turns the panorama, draws its `CubeMap`, then lays vanilla's overlay over it. `PanoramaRendererMixin` wraps that one `CubeMap.render(Minecraft, float, float)` call and hands it to `ExtrasPanorama.draw(minecraft, stock, render)`, where `stock` is the renderer's own cube map and `render` makes the original call on the cube map it is given, so the turn and the overlay stay vanilla's. Vanilla draws the panorama behind the main menu, behind every screen opened with no world, on `GenericMessageScreen` and on the level-loading screen (not through a portal), so Vetlas shows on all of them. Realms' empty world slots are not among them: they draw faces of the packs' panorama themselves, not through `PanoramaRenderer`. It is a draw-time swap, so it works without Wynntils' preload and over whichever panorama the packs give.

Vetlas is drawn only while the setting is on, `RetroModeManager.isEnabled()` is false, and Vetlas has not failed this session. Otherwise `stock` is drawn, and so it is while the faces are not downloaded: `load` gives no cube map and `stock` shows until they are here.

### Loading, releasing and failure

The state is a `CubeMap`, whether the texture is registered (`registered`), the faces being read, checked and decoded (`decoding`, a `CompletableFuture`) and a `failed` flag, all touched on the render thread only.

- **Loading.** The first draw that shows Vetlas asks `PanoramaDownloads.state(VETLAS)`. With the faces `HERE` it has them read back, each checked against its pinned size and SHA-256 (`PanoramaDownloads.read`), and decoded into the cube map's layers (`HostedCubeMapTexture.decode`), all on Minecraft's I/O threads (`Util.ioPool()`), and draws `stock` until that is done; the first draw after it makes a `HostedCubeMapTexture` at `LOCATION` (`vetsmod:textures/gui/title/background/vetlas`; no file of that name exists, the six faces' bytes are held in memory), uploads the layers into it (`apply`), registers it and makes a `CubeMap` of it, so the render thread neither waits on the disk nor decodes. The texture object itself is made on the render thread because `AbstractTexture` takes a sampler from `RenderSystem` as it is built. Without the faces it starts the download (unless one is running or failed) and draws `stock` meanwhile, trying again at the next draw. While registered the texture holds about 24 MB of GPU memory (six 1024×1024 RGBA faces) and reloads with every resource reload, on the reload's workers, as Minecraft's own panorama does.
- **Releasing.** The first panorama draw that does not show Vetlas, because the setting went off, retro came on or Vetlas failed, closes the `CubeMap`, releases the texture and drops a decode still running (its layers are closed when it ends), so the egg costs nothing while it is off. The next draw that shows it loads again. Nothing is released while a resource reload runs (while the loading overlay is up, whose fade-in still draws the screen beneath): that reload listed the texture as it started and uploads it again at its end, which into a released texture would keep about 24 MB that nothing holds until the game exits. The release waits for the first draw after the overlay has gone.
- **Failure.** `draw` throws only what drawing `stock` throws. Any failure while choosing, loading, decoding or drawing Vetlas sets `failed`, logs one warning ("Panorama: could not draw Vetlas; the main menu keeps its own panorama until the game restarts") and leaves `stock` drawn until the game restarts; the next draw outside a reload releases whatever is held (a release that itself fails is logged at debug), and the command's reply says so. A draw that fails draws `stock` in the same frame. A face that does not decode fails the decode, before anything is registered, so no later reload tries it. A resource reload never reads the disk for Vetlas: `HostedCubeMapTexture` builds its layers from the bytes it was given, which were checked against their pins before it was made, so a face deleted or changed later on disk cannot fail a reload, where a cube map that fails to load fails the whole reload and Minecraft answers by deselecting every resource pack. A folder that has changed (or lost a face) is found at the next `read`, deleted, and downloaded again; one that cannot be read, or deleted, latches `FAILED`, so it is not read again until the next start-up. A download that fails (offline, a wrong size or hash) is logged once, latches `FAILED` until the next start-up, and leaves `stock` drawn; this is not `failed`, which is about drawing.

### The faces and their download

The faces are 1024×1024 RGB PNGs, lossless, about 5.1 MB (4.9 MiB) in all: screenshots of Vetlas in Wynncraft's own (modern) textures, taken in game. They are not in the jar (it went from about 12.1 MB to 1.8 MB when both panoramas left it): they are hosted on i.wynnvets.org, and `HostedPanorama.VETLAS` pins each face's URL, size and SHA-256. They are downloaded into `vetsmod/panoramas/vetlas/<hash>` in the game folder (the hash is of the pins, so new pins make a new folder), each face checked and the folder written whole or not at all (`util/AtomicFolders`). `PanoramaDownloads` fetches one set on Minecraft's download threads (`Util.nonCriticalIoPool()`) when the egg is turned on or first drawn, with `state()` telling `HERE`, `DOWNLOADING`, `FAILED` or `MISSING`; a failed download is not retried until the next start-up. A player who never turns the egg on downloads nothing. Retro's Old Detlas is the other set ([vetsmod_retro.md](vetsmod_retro.md) §3). Both panoramas are credited in `credits.txt` and the README.

**To change Vetlas**: upload six new files to i.wynnvets.org through Chibisafe, never reusing an object (they are served immutable for a year), under top-level names only (nginx sends only `/<name>.<ext>` to the bucket); then update the six URLs, sizes and SHA-256s in `HostedPanorama.VETLAS`. The originals and a `SHA256SUMS`/`URLS.md` are in the gitignored `.claude/ephemeral/panoramas/`.

### Tests

`ExtrasCommandsTest` pins the tree, `PanoramaCommandTest` the reply's text and colour with each download state, `HostedPanoramaTest` the pins (top-level names on i.wynnvets.org, SHA-256s and folder names), `PanoramaDownloadsTest` that a face which is not the pinned file fails the download, that a changed or incomplete folder is deleted and an unreadable one is not read again, and that a face is read only from a `200` declaring its pinned length, and `HostedCubeMapTextureTest` the order of the cube map's layers. The download over the network, the draw, the load and the release need a running client and are not tested, nor are the setting's read and write, the command's run or the mixin's wiring.
