// Spaja ES module web verzije (src/main/web/app) u static/app/app.js (jezgro
// i Spisak) i po deo za svaki ostali ekran u static/app/chunks/.
// Maven ga pokreće pre testova i pakovanja (frontend-maven-plugin u pom.xml),
// pa jar uvek nosi app.js iz istog commit-a. Ručno: npm run build, ili
// npm run watch dok radi spring-boot:run (tada bez minifikacije).
//
// Izlaz je ES modul (format esm, splitting): import() u core/router.js postaje
// poseban deo, a ono što dele dva ekrana ide u zajednički deo. index.html
// učitava app.js kao <script type="module"> sa ?v=; delovi nose hash sadržaja
// u imenu, pa se ne menjaju dok se ne promene (sw.js ih čuva za rad bez mreže).
// es2020 je sintaksa koju je ručno pisan app.js već koristio, a import() i
// import.meta su u njoj.
import * as esbuild from 'esbuild';

const watch = process.argv.includes('--watch');
const options = {
  entryPoints: { app: 'src/main/web/app/main.js' },
  outdir: process.env.WEB_OUT_DIR || 'target/classes/static/app',
  entryNames: '[name]',
  chunkNames: 'chunks/[name]-[hash]',
  bundle: true,
  splitting: true,
  format: 'esm',
  target: 'es2020',
  banner: { js: '"use strict";' },
  minify: !watch,
  legalComments: 'none',
  charset: 'utf8',
  logLevel: 'warning'
};

if (watch) {
  const context = await esbuild.context(options);
  await context.watch();
  console.log('esbuild: pratim src/main/web/app, izlaz', options.outdir);
} else {
  await esbuild.build(options);
}
