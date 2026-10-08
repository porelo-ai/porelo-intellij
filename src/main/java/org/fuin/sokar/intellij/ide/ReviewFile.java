package org.fuin.sokar.intellij.ide;

import org.fuin.sokar.intellij.gate.Waiting;

import com.intellij.testFramework.LightVirtualFile;

/**
 * The editor tab of one waiting push; it holds what was read, never a file of the person's.
 */
final class ReviewFile extends LightVirtualFile {

    private final transient Waiting.Entry entry;

    private final transient ReviewTab.Read read;

    ReviewFile(Waiting.Entry entry, ReviewTab.Read read) {
        super(entry.push().name() + ": " + entry.push().subject());
        this.entry = entry;
        this.read = read;
        setWritable(false);
    }

    Waiting.Entry entry() {
        return entry;
    }

    ReviewTab.Read read() {
        return read;
    }

}
