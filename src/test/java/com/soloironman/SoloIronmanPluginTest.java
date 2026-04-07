package com.soloironman;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SoloIronmanPluginTest {
    public static void main(String[] args) throws Exception {
        // This line tells the client to load your specific plugin
        ExternalPluginManager.loadBuiltin(SoloIronmanPlugin.class);
        // This line starts the actual RuneLite game client
        RuneLite.main(args);
    }
}
