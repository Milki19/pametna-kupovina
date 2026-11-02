// Spaja ES module web verzije (src/main/web/app) u jedan static/app/app.js.
// Maven ga pokreće pre testova i pakovanja (frontend-maven-plugin u pom.xml),
// pa jar uvek nosi app.js iz istog commit-a. Ručno: npm run build, ili
// npm run watch dok radi spring-boot:run (tada bez minifikacije).
//
// Izlaz je obična skripta (IIFE), ne modul: index.html je učitava kao i do
// sada, sa ?v=, a document.currentScript i sw.js rade isto. es2020 je sintaksa
// koju je ručno pisan app.js već koristio, pa minifikacija ne dodaje noviju.
import * as esbuild from 'esbuild';

const watch = process.argv.includes('--watch');
const options = {
  entryPoints: ['src/main/web/app/main.js'],
  outfile: process.env.WEB_OUT || 'target/classes/static/app/app.js',
  bundle: true,
  format: 'iife',
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
  console.log('esbuild: pratim src/main/web/app, izlaz', options.outfile);
} else {
  await esbuild.build(options);
}
