package com.oriondb.db;

import java.util.List;

/**
 * @param skipDuplicates skip games whose moves and players match a game already present
 * @param labels         labels to put on every imported game
 * @param atomic         reject the whole import if any game is invalid
 */
public record ImportOptions(boolean skipDuplicates, List<String> labels, boolean atomic) {
    public static final ImportOptions DEFAULT = new ImportOptions(true, List.of(), false);

    public ImportOptions withLabels(List<String> l) {
        return new ImportOptions(skipDuplicates, List.copyOf(l), atomic);
    }

    public ImportOptions withSkipDuplicates(boolean b) {
        return new ImportOptions(b, labels, atomic);
    }

    public ImportOptions withAtomic(boolean b) {
        return new ImportOptions(skipDuplicates, labels, b);
    }
}
