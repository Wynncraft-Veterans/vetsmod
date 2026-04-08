package org.wynnvets.api.auth;

import java.util.Map;

/**
 * Provides authentication credentials for outbound API connections.
 *
 * <p>Implementations supply headers or tokens that are attached to
 * WebSocket connection requests. The current implementation is
 * {@link NoOpAuthProvider}, which passes no credentials. Future
 * implementations (e.g. Dazebot OAuth) can supply real tokens by
 * implementing this interface and registering them in
 * {@link org.wynnvets.api.V1ApiManager}.</p>
 *
 * <p><b>Lifecycle:</b> A provider is instantiated once at mod init.
 * {@link #getHeaders()} may be called on any thread whenever a new
 * WebSocket connection is established.</p>
 */
public interface AuthProvider {

  /**
   * Returns additional HTTP headers to attach to WebSocket upgrade
   * requests. The returned map should be unmodifiable.
   *
   * @return header name-value pairs; empty map if no auth is needed
   */
  Map<String, String> getHeaders();

  /**
   * Whether this provider is actively supplying credentials.
   *
   * @return {@code true} if authentication headers will be attached
   */
  boolean isAuthenticated();
}
