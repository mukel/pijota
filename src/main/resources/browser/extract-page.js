// Visible-only DOM walk → markdown: leaf blocks only (no parent/child dupes), inline links, code, capped.
(() => {
  const vis = el => {
    const r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) return false;
    const s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none' && parseFloat(s.opacity || '1') > 0;
  };
  const seen = new Set(), out = [];
  let links = 0;
  const title = (document.title || '').trim();
  if (title) out.push('# ' + title);
  out.push(location.href); out.push('');
  const BLOCK = 'h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,td,th,figcaption,'
    + '[class*="comment-body"],[class*="comment-content"],[data-testid*="comment"]';
  for (const el of document.querySelectorAll(BLOCK)) {
    if (!vis(el)) continue;
    const tag = el.tagName.toLowerCase();
    if (tag !== 'pre' && el.querySelector(BLOCK)) continue; // only leaf blocks → no parent+child dupes
    if (tag === 'pre') {
      const code = (el.innerText || '').replace(/\s+$/, '');
      if (code) { out.push('```'); out.push(code); out.push('```'); }
      continue;
    }
    let parts = [];
    for (const node of el.childNodes) {
      if (node.nodeType === 3) parts.push(node.textContent);
      else if (node.nodeType === 1) {
        if (node.tagName === 'A' && node.href && /^https?:/.test(node.href)) {
          const t = (node.innerText || '').trim();
          if (t) { parts.push(links < 80 ? '[' + t.slice(0, 160) + '](' + node.href + ')' : t); links++; }
        } else parts.push(node.innerText || node.textContent || '');
      }
    }
    let text = parts.join('').replace(/\s+/g, ' ').trim();
    if (!text || seen.has(text)) continue;
    seen.add(text);
    let prefix = '';
    if (/^h[1-6]$/.test(tag)) prefix = '#'.repeat(+tag[1]) + ' ';
    else if (tag === 'li') prefix = '- ';
    else if (tag === 'blockquote') prefix = '> ';
    out.push(prefix + text);
    if (out.length > 5000) break;
  }
  let md = out.join('\n');
  if (md.length > 900000) md = md.slice(0, 900000) + '\n…[truncated]';
  return md;
})()
