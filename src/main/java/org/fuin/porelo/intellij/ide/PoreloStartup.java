package org.fuin.porelo.intellij.ide;

import com.intellij.ide.AppLifecycleListener;
import java.util.List;

/**
 * Starts the watch when the IDE starts, so a notification comes whatever project is open, or none.
 */
public final class PoreloStartup implements AppLifecycleListener {

    @Override
    public void appFrameCreated(List<String> commandLineArgs) {
        PoreloWatch.get().start();
    }
}
