package org.wynnvets.datamodels;

public class Guild {
    private String uuid;
    private String name;
    private String prefix;
    private String rank;
    private String rankStars;

    @Override
    public String toString() {
        return "GUILD INFO:\n" + uuid + "\n" + name;
    }
}
