import { readFileSync, readdirSync } from 'node:fs';
import vm from 'node:vm';
for (const file of ['app/src/main/assets/ai_tutor.html','app/src/main/assets/player.html']) {
  const html=readFileSync(new URL(`../${file}`, import.meta.url),'utf8');
  const scripts=[...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)].map(m=>m[1]).filter(Boolean);
  for(const script of scripts) new vm.Script(script,{filename:file});
}
for(const file of readdirSync(new URL('../app/src/main/assets/',import.meta.url)).filter(name=>name.endsWith('.js'))){new vm.Script(readFileSync(new URL(`../app/src/main/assets/${file}`,import.meta.url),'utf8'),{filename:file});}
console.log('Embedded tutor and player JavaScript parsed successfully.');
