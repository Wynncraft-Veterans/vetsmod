package org.wynnvets.mixin.client.command;

import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.guild.GuildStateManager;

/**
 * Intercepts the {@code /unlock <key>} command on the client side so the key
 * is consumed by vetsmod instead of being sent to the Wynncraft server.
 *
 * <p>The key is a bearer token issued by dazebot's {@code /vetsmod} Discord
 * command. Once stored, vetsmod sends it in an {@code auth} frame on every
 * v1 WebSocket (re)connect; the server's response asynchronously confirms
 * (or rejects) the user's tier. See {@link org.wynnvets.guild.UnlockManager} and
 * {@link org.wynnvets.api.V1ApiManager} for the surrounding flow.</p>
 */
@Mixin(ClientPacketListener.class)
public class UnlockCommandMixin {

    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void onSendCommand(String command, CallbackInfo ci) {
        if (!command.startsWith("unlock")) {
            return;
        }
        // Match exactly "/unlock" or "/unlock <something>" — anything else is a
        // command that just happens to start with "unlock" (e.g. a hypothetical
        // "/unlocksomething") and we leave it for the server to handle.
        if (command.length() != "unlock".length() && command.charAt("unlock".length()) != ' ') {
            return;
        }

        ci.cancel();

        String[] parts = command.split(" ", 2);
        if (parts.length < 2 || parts[1].isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    "Usage: /unlock <key>  (run ~vetsmod in #bot-commands "
                                            + "at https://wynnvets.org/discord to get a key)")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        handleUnlock(parts[1].trim());
    }

    /**
     * Process the unlock attempt. The local outcome (key shape, persistence)
     * is reported synchronously here; the server's verdict on the key arrives
     * asynchronously via {@link GuildStateManager#onAuthSuccess(String)} /
     * {@link GuildStateManager#onAuthFailure(String)} once the auth-frame ack
     * lands.
     */
    private void handleUnlock(String key) {
        GuildStateManager.UnlockAttemptResult result = GuildStateManager.tryUnlock(key);

        switch (result) {
            case STORED_VERIFYING ->
                    ChatUtils.sendLocalMessage(
                            Component.literal(
                                            "⏳ Saved your vetsmod key. Verifying with the server…")
                                    .withStyle(ChatFormatting.YELLOW));
            case MALFORMED ->
                    ChatUtils.sendLocalMessage(
                            Component.literal(
                                            "❌ That doesn't look like a vetsmod key. "
                                                    + "Run ~vetsmod in #bot-commands at https://wynnvets.org/discord "
                                                    + "to get one.")
                                    .withStyle(ChatFormatting.RED));
            case MISSING_KEY ->
                    ChatUtils.sendLocalMessage(
                            Component.literal("Usage: /unlock <key>")
                                    .withStyle(ChatFormatting.RED));
        }
    }
}
