package org.wynnvets.constants;

import java.net.URI;

public class MJApi {
  public static URI GetUserUUID(String userName) {
    return URI.create("https://api.mojang.com/users/profiles/minecraft/" + userName);
  }
}
