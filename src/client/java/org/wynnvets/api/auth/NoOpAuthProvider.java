package org.wynnvets.api.auth;

import java.util.Collections;
import java.util.Map;

/**
 * A no-op authentication provider that supplies no credentials.
 *
 * <p>This is the default provider used when no external authentication
 * system (e.g. Dazebot) is configured. All connections proceed without
 * authentication headers.</p>
 */
public final class NoOpAuthProvider implements AuthProvider {

  @Override
  public Map<String, String> getHeaders() {
    return Collections.emptyMap();
  }

  @Override
  public boolean isAuthenticated() {
    return false;
  }
}
