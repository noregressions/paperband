// Measures a paged document in millimetres: every sheet (section.page) and every box in it
// (an element with a data-box path), relative to the sheet's top-left corner; images that
// didn't load; fonts that failed. Returned as plain objects for ChromiumBrowser to read.
() => {
  const MM = 96 / 25.4;
  const mm = px => Math.round(px / MM * 100) / 100;
  // How much taller the content is than the box. A box cut after some lines (line-clamp, the
  // truncate remedy) still counts the hidden lines in its scroll height, so what it shows is
  // measured instead: at its natural height, a clamped box is exactly its visible lines.
  const overflowY = el => {
    const clamp = getComputedStyle(el).webkitLineClamp;
    if (!clamp || clamp === 'none') {
      return el.scrollHeight - el.clientHeight;
    }
    const style = el.style.cssText;
    const fixed = el.getBoundingClientRect().height;
    el.style.cssText = style + ';height:auto!important;max-height:none!important;min-height:0!important;'
      + 'flex:none!important;align-self:start!important';
    const shown = el.getBoundingClientRect().height;
    el.style.cssText = style;
    return shown - fixed;
  };
  const pages = [];
  for (const sheet of document.querySelectorAll('section.page')) {
    const s = sheet.getBoundingClientRect();
    const cs = getComputedStyle(sheet);
    const boxes = [];
    for (const el of sheet.querySelectorAll('[data-box]')) {
      const b = el.getBoundingClientRect();
      let ox = el.scrollWidth - el.clientWidth;
      // Code and tables can scroll or clip inside the box: their hidden width doesn't fit either.
      for (const wide of el.querySelectorAll('pre, table')) {
        ox = Math.max(ox, wide.scrollWidth - wide.clientWidth);
      }
      // A box that splits marks where each of its units starts (data-unit); an each box marks its items.
      const units = [...el.children].filter(c => c.hasAttribute('data-unit'))
        .map(c => mm(c.getBoundingClientRect().top - b.top));
      boxes.push({
        path: el.dataset.box,
        x: mm(b.left - s.left), y: mm(b.top - s.top), w: mm(b.width), h: mm(b.height),
        overflowX: mm(Math.max(0, ox)), overflowY: mm(Math.max(0, overflowY(el))),
        units, content: mm(el.scrollHeight),
      });
    }
    pages.push({
      id: sheet.id, width: mm(s.width), height: mm(s.height),
      margin: [cs.paddingTop, cs.paddingRight, cs.paddingBottom, cs.paddingLeft].map(v => mm(parseFloat(v))),
      overflowX: mm(Math.max(0, sheet.scrollWidth - sheet.clientWidth)),
      overflowY: mm(Math.max(0, sheet.scrollHeight - sheet.clientHeight)),
      boxes,
    });
  }
  const images = [...document.images]
    .filter(img => !img.complete || img.naturalWidth === 0)
    .map(img => ({
      page: img.closest('section.page')?.id ?? '',
      box: img.closest('[data-box]')?.dataset.box ?? '',
      src: img.getAttribute('src') ?? '',
    }));
  const fonts = [...document.fonts]
    .filter(f => f.status === 'error')
    .map(f => `${f.family.replace(/"/g, '')} ${f.weight} ${f.style}`);
  return { pages, images, fonts };
}
