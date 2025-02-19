package com.otaliastudios.transcoder.source;

import androidx.annotation.NonNull;

/**
 * A {@link DataSource} that clips the inner source within the given interval.
 */
@SuppressWarnings("unused")
public class ClipDataSource extends DataSourceWrapper {

    public ClipDataSource(@NonNull DataSource source, long clipStartUs) {
        super(new TrimDataSource(source, clipStartUs));
    }

    public ClipDataSource(@NonNull DataSource source, long clipStartUs, long clipEndUs) {
        super(new TrimDataSource(source,
                clipStartUs,
                Math.max(getSourceDurationUs(source) - clipEndUs, 0L)));
    }

    private static long getSourceDurationUs(@NonNull DataSource source) {
        if (!source.isInitialized()) source.initialize();
        return source.getDurationUs();
    }
}
