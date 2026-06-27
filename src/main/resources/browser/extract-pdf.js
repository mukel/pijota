// extract-pdf.js — extract a PDF's text layer as markdown, in the page, via the bundled (downloaded) PDF.js.
//
// Run by BrowserSession.extractPdf through BrowserBackend.evaluate (which awaits the returned promise). Preconditions
// set by the caller before this runs:
//   • pdf.min.js has already been evaluated (defines the PDF.js UMD global)
//   • globalThis.__pdfWorker = the pdf.worker.min.js source (used to build a Blob worker, no served file needed)
//   • globalThis.__pdfB64    = base64 of the PDF bytes
//
// Returns a markdown-ish string (pages separated by `---`). Digital / text-layer PDFs only — a scanned (image-only)
// PDF has no text layer and yields little; the caller falls back to a native attachment / note in that case.
(async () => {
  // the legacy UMD build exposes the lib under the webpack library name; older builds used `pdfjsLib`
  const pdfjsLib = globalThis["pdfjs-dist/build/pdf"] || globalThis.pdfjsLib;
  if (!pdfjsLib || !pdfjsLib.getDocument) return "[pdf.js failed to load]";

  try {
    const blob = new Blob([globalThis.__pdfWorker], { type: "text/javascript" });
    pdfjsLib.GlobalWorkerOptions.workerSrc = URL.createObjectURL(blob);
  } catch (e) {
    // no worker → PDF.js falls back to running on the main thread (slower but works)
  }

  let doc;
  try {
    const bin = atob(globalThis.__pdfB64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    doc = await pdfjsLib.getDocument({ data: bytes, disableFontFace: true, isEvalSupported: false }).promise;
  } catch (e) {
    return "[pdf parse failed: " + (e && e.message ? e.message : e) + "]";
  }

  const MAX_PAGES = 300;
  const pages = [];
  const n = Math.min(doc.numPages, MAX_PAGES);
  for (let p = 1; p <= n; p++) {
    const page = await doc.getPage(p);
    const tc = await page.getTextContent();
    // group glyph runs into lines by their y position so reading order survives
    const lines = [];
    let line = [];
    let lastY = null;
    for (const it of tc.items) {
      const y = it.transform ? Math.round(it.transform[5]) : lastY;
      if (lastY !== null && y !== null && Math.abs(y - lastY) > 2 && line.length) {
        lines.push(line.join(""));
        line = [];
      }
      line.push(it.str || "");
      if (it.hasEOL) {
        lines.push(line.join(""));
        line = [];
        lastY = null;
        continue;
      }
      lastY = y;
    }
    if (line.length) lines.push(line.join(""));
    pages.push(lines.join("\n").replace(/[ \t]+$/gm, "").trim());
  }
  return pages.filter(Boolean).join("\n\n---\n\n");
})()
