package org.wynnvets.datamodels;

public class User {
    private String username;
    private String uuid;
    private Guild guild;

    @Override
    public String toString() {
        return "USER INFO:\n" + username + "\n" + uuid + "\n" + guild;
    }
}
