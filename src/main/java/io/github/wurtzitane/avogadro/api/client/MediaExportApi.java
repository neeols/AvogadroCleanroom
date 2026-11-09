package io.github.wurtzitane.avogadro.api.client;

import java.io.IOException;

import io.github.wurtzitane.avogadro.client.export.MoleculeMediaExport;

/**
 * Starts GIF or MP4 exports using the same export engine as the in-game commands.
 *
 * <p>
 * Submit requests on the Minecraft client thread. Only one export can run at a time; a second submission is rejected
 * rather than queued. The returned job reports progress and supports cancellation.
 */
public final class MediaExportApi {

    /** The singleton instance of {@link MediaExportApi}. */
    static final MediaExportApi INSTANCE = new MediaExportApi();

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
