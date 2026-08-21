package org.wynnvets.fetcher.ondemand;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
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
public class ReturnFetcher {
    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    /**
     * Deep link to the Discord channel the return post is mirrored from — the
     * API serves the message body only, with no ID to jump to, so the footer
     * points at the channel and lets the latest post sit at the bottom of it.
     */
    private static final String RETURN_CHANNEL_URL =
            "https://discord.com/channels/1313769181321236490/1320597026118828112";

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
                                    Minecraft minecraft = Minecraft.getInstance();
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
                                    if (component == null) {
                                        return Component.literal(
                                                "Error: Received null component from API");
                                    }
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
                                    return Component.literal(
                                            "Error parsing return data: " + e.getMessage());
                                }
                            } else {
                                return Component.literal(
                                        "Failed to fetch return (Status: "
                                                + response.statusCode()
                                                + ")");
                            }
                        })
                .exceptionally(e -> Component.literal("Error fetching return: " + e.getMessage()));
    }

    /**
     * Closing line of a {@code /wv return} printout, pointing at the Discord
     * post the body was mirrored from.
     *
     * <p>Spec is {@code &6&oFor more info, click &e&n&ohere &6for the
     * announcement post!} — note the trailing {@code &6}, which under vanilla
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
                                        Component.literal("Open the announcement post in Discord")
                                                .withStyle(ChatFormatting.GRAY)));
        Style gold =
                Style.EMPTY.withColor(ChatFormatting.GOLD).withItalic(false).withUnderlined(false);

        return Component.literal("For more info, click ")
                .setStyle(goldItalic)
                .append(Component.literal("here").setStyle(linkStyle))
                .append(Component.literal(" for the announcement post!").setStyle(gold));
    }
}
