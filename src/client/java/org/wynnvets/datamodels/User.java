package org.wynnvets.datamodels;

public class User {
    private String username;
    private String uuid;
    private Guild guild;
    private String firstJoin;

    @Override
    public String toString() {
        return "USER INFO:\n" + username + "\n" + uuid + "\n" + guild;
    }

    public String getUserAge() {
        return firstJoin;
    }
}
