// Search-results extraction: the result links (google chrome/redirects cleaned) plus a page-text snapshot.
(() => {
  const out = [], seen = new Set();
  const clean = href => {
    if (/\/url\?/.test(href)) {
      const m = href.match(/[?&](?:q|url)=([^&]+)/);
      if (m) { try { return decodeURIComponent(m[1]); } catch (e) {} }
    }
    return href;
  };
  for (const h3 of document.querySelectorAll('a h3')) {
    const a = h3.closest('a'); if (!a) continue;
    const href = clean(a.href); if (!/^https?:/.test(href)) continue;
    if (/(^https?:\/\/(www\.)?google\.)|gstatic\.|googleusercontent\./.test(href)) continue;
    const title = (h3.innerText || '').trim(); if (!title) continue;
    if (seen.has(href)) continue; seen.add(href);
    out.push('- [' + title + '](' + href + ')');
    if (out.length >= 20) break;
  }
  let md = out.length ? '## Results\n' + out.join('\n') : '';
  const snap = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ').trim().slice(0, 1200);
  if (snap) md += (md ? '\n\n' : '') + '## Page text\n' + snap;
  return md;
})()
