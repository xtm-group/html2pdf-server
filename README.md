# html2pdf-server
Source code for the server side of the upcoming PDF renderer for XTRF

## Configuration

Page background images are bounded so that an image a user is permitted to upload cannot decide how
much heap a render costs. Five JVM system properties control it:

| property | default | effect |
|---|---|---|
| `eu.xtrf.html2pdf.image.backgroundOptimizationEnabled` | `true` | `false` restores the previous behaviour on the same build: the theme stylesheet is handed to the renderer untouched and no image is inspected. |
| `eu.xtrf.html2pdf.image.optimizeAbovePixels` | `12000000` | Backgrounds at or below this pixel count are served exactly as uploaded. The stock letterhead (2480x3508, A4 at 300 DPI) is below it, so it is never rewritten. |
| `eu.xtrf.html2pdf.image.targetPixels` | `8700000` | A background above the optimisation threshold is served downscaled to at most this pixel count. |
| `eu.xtrf.html2pdf.image.maxPixels` | `30000000` | A background above this pixel count is never decoded at all; a blank page-proportioned substitute is served instead. |
| `eu.xtrf.html2pdf.image.maxCacheEntries` | `16` | How many downscaled or substituted backgrounds are kept in the cross-render cache. A background served as uploaded is not cached, so no full-size source image is retained between renders. |

Above `maxPixels` the page renders with a blank background instead of the uploaded image, and a
`WARN` names the image, its dimensions and the property that set the limit. Raise `maxPixels` to
allow a larger image.

These are read once, when the Spring bean is constructed, so a change takes effect only after a
restart of the JVM that hosts the renderer. That JVM is the XTRF application server: `xtrf-commons`
consumes this module as an in-process compile dependency, not over HTTP.
