package org.wynnvets;

import net.fabricmc.api.ModInitializer;

import org.wynnvets.logging.VetsLogger;

/**
 * Server-side entry point for the VetsMod Fabric mod.
 *
 * <p>Performs minimal initialization since all mod functionality is client-side.
 * The common initializer establishes the shared mod ID constant used across
 * the codebase.</p>
 */
public class Vetsmod implements ModInitializer {
  public static final String MOD_ID = "vetsmod";

  @Override
  public void onInitialize() {
    VetsLogger.info("Common initializer loaded");
  }
}