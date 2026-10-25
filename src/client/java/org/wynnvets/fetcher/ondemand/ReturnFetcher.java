package org.wynnvets.fetcher.ondemand;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.wynntils.utils.mc.McUtils;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.api.VetsApi;
import org.wynnvets.chat.DiscordTimestamps;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.HttpClients;

/**
 * On-demand fetcher for the guild's current return event information.
 *
 * <p>Queries the VetsMod API for the active return event and formats the
 * response as a chat component for display via {@code /wv return}.</p>
 */
public final class ReturnFetcher {
    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    /**
     * Deep link to the Discord channel the return post is mirrored from — the
     * API serves the message body only, with no ID to jump to, so the footer
     * points at the channel and lets the latest post sit at the bottom of it.
     */
    private static final String RETURN_CHANNEL_URL =
            "https://discord.com/channels/1313769181321236490/1320597026118828112";

    /** Shown in place of the post when the request fails, the API answers with an error, or the
     *  post can't be read. Worded like {@link MotdFetcher}'s line for the same outage. */
    private static final String UNAVAILABLE_LINE =
            "Return Unavailable: Unable to reach the vets API, which may be down.";

    private ReturnFetcher() {}

    /**
     * Fetches the Return information from the API asynchronously
     *
     * @return CompletableFuture containing the Return Component
     */
    public static CompletableFuture<MutableComponent> fetchReturn() {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(VetsApi.RETURN)
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

        return HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .<MutableComponent>thenApply(
                        response -> {
                            VetsLogger.debug("Return fetch response: {}", response.statusCode());
                            if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                                try {
                                    // Parse the JSON response and convert it to a Component
                                    Minecraft minecraft = McUtils.mc();
                                    JsonElement json = JsonParser.parseString(response.body());
                                    Component component =
                                            ComponentSerialization.CODEC
                                                    .parse(
                                                            minecraft
                                                                    .level
                                                                    .registryAccess()
                                                                    .createSerializationContext(
                                                                            JsonOps.INSTANCE),
                                                            json)
                                                    .getOrThrow();
                                    // The post is authored in Discord, so it carries Discord's
                                    // <t:...> timestamp markup — expand it into the reader's local
                                    // time before display.
                                    MutableComponent rendered = DiscordTimestamps.expand(component);
                                    if (rendered.getString().isBlank()) {
                                        // No post cached upstream; nothing to point a footer at.
                                        return rendered;
                                    }
                                    return rendered.append(Component.literal("\n\n"))
                                            .append(announcementFooter());
                                } catch (Exception e) {
                                    VetsLogger.warn(
                                            "Return post could not be read: {}", e.toString());
                                    return Component.literal(UNAVAILABLE_LINE);
                                }
                            } else {
                                VetsLogger.debug(
                                        "Return fetch returned status {}", response.statusCode());
                                return Component.literal(UNAVAILABLE_LINE);
                            }
                        })
                .exceptionally(
                        e -> {
                            VetsLogger.debug("Return fetch failed: {}", e.toString());
                            return Component.literal(UNAVAILABLE_LINE);
                        });
    }

    /**
     * Closing line of a {@code /wv return} printout, pointing at the Discord
     * channel the post was mirrored from (see {@link #RETURN_CHANNEL_URL}), and its
     * hover text says so.
     *
     * <p>Spec is {@code &6&oFor more info, see the original announcement
     * post in &e&n&othis &6discord channel!} — note the trailing {@code &6}, which under vanilla
     * legacy-code semantics resets the italic and underline, so the tail renders
     * plain gold.</p>
     */
    private static MutableComponent announcementFooter() {
        Style goldItalic = Style.EMPTY.withColor(ChatFormatting.GOLD).withItalic(true);
        Style linkStyle =
                Style.EMPTY
                        .withColor(ChatFormatting.YELLOW)
                        .withItalic(true)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(RETURN_CHANNEL_URL)))
                        .withHoverEvent(
                                new HoverEvent.ShowText(
                                        Component.literal("Open the return channel in Discord")
                                                .withStyle(ChatFormatting.GRAY)));
        Style gold =
                Style.EMPTY.withColor(ChatFormatting.GOLD).withItalic(false).withUnderlined(false);

        return Component.literal("For more info, see the original announcement post in ")
                .setStyle(goldItalic)
                .append(Component.literal("this").setStyle(linkStyle))
                .append(Component.literal(" discord channel!").setStyle(gold));
    }
}
