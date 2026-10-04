package com.anil.jarvis;

import android.app.Application;

/** Jarvis's process starts here: a backup he chose to bring back goes in place before anything reads the old data. */
public class JarvisApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Backup.applyPending(this);
    }
}
