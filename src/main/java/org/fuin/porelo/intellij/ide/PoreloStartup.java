package org.fuin.porelo.intellij.ide;

import java.util.List;

import com.intellij.ide.AppLifecycleListener;

/**
 * Starts the watch when the IDE starts, so a notification comes whatever project is open, or none.
 */
public final class PoreloStartup implements AppLifecycleListener {

    @Override
    public void appFrameCreated(List<String> commandLineArgs) {
        PoreloWatch.get().start();
    }

}
