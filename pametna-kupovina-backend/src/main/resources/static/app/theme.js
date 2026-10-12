// Tema se postavlja pre crtanja strane, da tamna tema ne bljesne svetlom.
// Bez izbora strana prati sistem (tamni sistem je siva tema).
(function () {
  const bars = { light: '#f5f6fa', dark: '#1c1d21', black: '#050506' };
  function apply(theme) {
    const root = document.documentElement;
    if (bars[theme]) root.dataset.theme = theme; else delete root.dataset.theme;
    const shown = bars[theme] ? theme : matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = bars[shown];
  }
  let theme = null;
  try { theme = JSON.parse(localStorage.getItem('pk.theme')); } catch (e) { /* bez podešavanja */ }
  apply(theme);
  window.applyTheme = value => { theme = value; apply(value); };
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => apply(theme));
})();
