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

    public String getFirstJoinDate() {
        return String.format("User %s joined on %s", username, firstJoin.split("T")[0]);
    }
}
