// Clicks a cookie/consent "accept" control (substring + multilingual, length-bounded to avoid prose).
(() => {
  const rx = /(accept all|accept|i agree|agree|allow all|got it|alle akzeptieren|tout accepter|accetta tutto|aceptar todo|consent)/i;
  for (const b of document.querySelectorAll('button, [role=button], input[type=submit], a')) {
    const t = (b.innerText || b.value || '').trim();
    if (t && t.length < 40 && rx.test(t)) { b.click(); return t; }
  }
  return '';
})()
