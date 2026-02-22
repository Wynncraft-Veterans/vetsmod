package org.wynnvets.util.colors;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class GradientTextBuilder {
  private GradientTextBuilder() {
  }

  public static MutableComponent linear(String text, int startRgb, int endRgb) {
    MutableComponent component = Component.empty();

    if (text == null || text.isEmpty()) {
      return component;
    }

    if (text.length() == 1) {
      return Component.literal(text).withColor(startRgb);
    }

    for (int i = 0; i < text.length(); i++) {
      float t = i / (float) (text.length() - 1);
      int rgb = interpolateRgb(startRgb, endRgb, t);
      component.append(Component.literal(String.valueOf(text.charAt(i))).withColor(rgb));
    }

    return component;
  }

  private static int interpolateRgb(int startRgb, int endRgb, float t) {
    int startR = (startRgb >> 16) & 0xFF;
    int startG = (startRgb >> 8) & 0xFF;
    int startB = startRgb & 0xFF;

    int endR = (endRgb >> 16) & 0xFF;
    int endG = (endRgb >> 8) & 0xFF;
    int endB = endRgb & 0xFF;

    int r = Math.round(startR + (endR - startR) * t);
    int g = Math.round(startG + (endG - startG) * t);
    int b = Math.round(startB + (endB - startB) * t);

    return (r << 16) | (g << 8) | b;
  }
}