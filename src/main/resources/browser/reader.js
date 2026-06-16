// Firefox "reader mode" wrapper. The Readability library is injected separately (once per page, via
// Page.addScriptToEvaluateOnNewDocument); this runs it on a clone of the rendered DOM and returns the article
// (or null for non-article pages → the caller falls back to the DOM walk). The marker below is a test seam.
(function () { /* __pijota_reader__ */
  try {
    if (typeof Readability === 'undefined') return null;
    var art = new Readability(document.cloneNode(true)).parse();
    if (!art || !art.content || ((art.textContent || '').trim().length) < 400) return null;
    return { title: art.title || '', byline: art.byline || '', content: art.content };
  } catch (e) { return null; }
})()
