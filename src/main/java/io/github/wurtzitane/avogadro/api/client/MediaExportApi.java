package io.github.wurtzitane.avogadro.api.client;

import io.github.wurtzitane.avogadro.client.export.MoleculeMediaExport;
import java.io.IOException;

/**
 * Client-thread submission API to the same bounded export engine used by commands.
 */
public final class MediaExportApi {

    /** The singleton instance of {@link MediaExportApi}. */
    static final MediaExportApi INSTANCE = new MediaExportApi();

    /**
     * Private constructor to enforce the singleton pattern.
     */
    private MediaExportApi() {}

    /**
     * Submits a media export request for execution.
     *
     * @param request the media export request to process
     * @return the {@link ExportJob} tracking the export's progress and result
     * @throws IOException if an I/O error occurs during submission
     */
    public ExportJob submit(MediaExportRequest request) throws IOException {
        return MoleculeMediaExport.submit(request);
    }
}