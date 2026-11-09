/**
 * Draws molecules and exports media on the Minecraft client. Do not load these classes on a dedicated server.
 *
 * <p>
 * Worked guides: <a href="../doc-files/rendering.html">custom viewers</a> and <a href="../doc-files/exports.html">media
 * exports with real output</a>. Select Java or Groovy in either guide; JSON examples cover the stored definitions and
 * recipes.
 *
 * <h2>Draw a model</h2>
 * <p>
 * Call rendering methods from a screen's drawing method or another render-thread callback.
 * {@link io.github.wurtzitane.avogadro.api.client.MoleculeRenderOptions} lets one draw use its own camera angles, zoom,
 * colors, and sizes without changing other views.
 *
 * <h2>Export an animation</h2>
 * <p>
 * Submit an export request on the Minecraft client thread. Only one job can run at a time. For example, if
 * {@code molecule} is a registered molecule definition:
 *
 * <pre>{@code
 * ExportJob job = AvogadroClientApi.exports().submit(MediaExportRequest.molecule(molecule).build());
 * job.onComplete(result -> {
 *     // Read result.state() to distinguish success, cancellation, and failure.
 * });
 * }</pre>
 * <p>
 * Submission can throw {@link java.io.IOException}, for example if the output file already exists.
 * {@link io.github.wurtzitane.avogadro.api.client.MediaExportJob#progress()} can be read from any thread. Completion
 * listeners run on the client thread after resources have been cleaned up, including listeners added after the job
 * finished. API jobs do not send chat messages by default.
 */
package io.github.wurtzitane.avogadro.api.client;
