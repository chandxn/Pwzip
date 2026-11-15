import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const html=readFileSync(new URL('../app/src/main/assets/ai_tutor.html',import.meta.url),'utf8');
const scripts=[...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)].map(m=>m[1]).filter(Boolean);
const elements=new Map();
const element=id=>{if(!elements.has(id))elements.set(id,{id,textContent:'',innerHTML:'',value:'',style:{},options:[],className:'',classList:{add(){},remove(){}},appendChild(){}});return elements.get(id);};
const storage=new Map();
let vaultedToken='',vaultedServer='';
const context=vm.createContext({
  window:{addEventListener(){},NativeTutorSession:{putToken(server,token){vaultedServer=server;vaultedToken=token;return true;},getToken(server){return server===vaultedServer?vaultedToken:'';},clearToken(){vaultedToken='';vaultedServer='';}}},
  document:{getElementById:element,addEventListener(){},visibilityState:'visible'},
  localStorage:{getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,String(value)),removeItem:key=>storage.delete(key)},
  navigator:{onLine:true},
  URL,
  console,
  setTimeout:()=>0,
  clearTimeout(){},
  fetch:async()=>{throw new Error('Unexpected network request during UI unit test.')}
});
vm.runInContext(scripts.at(-1),context,{filename:'ai_tutor.html'});

const first=vm.runInContext(`applySessionState({hasActiveLesson:true,courseId:'course-a',lessonId:'lesson-123',lessonTitle:'Lesson A',lastUpdatedAt:1000,seq:1})`,context);
const switchedCourse=vm.runInContext(`applySessionState({hasActiveLesson:true,courseId:'course-b',lessonId:'lesson-123',lessonTitle:'Lesson A',lastUpdatedAt:2000,seq:2})`,context);
assert.equal(first.lessonChanged,true);
assert.equal(switchedCourse.lessonChanged,true,'reusing a lesson ID in a different course must reset tutor context');
assert.equal(switchedCourse.contextEpoch,2);

vm.runInContext(`renderStudyMode(${JSON.stringify({summary:'Grounded summary',chapters:[{start_seconds:65,title:'Chapter One',summary:'First section'}],coverage:{guide_cues:12,captured_cues:100}})},'SUMMARY')`,context);
assert.match(element('studyOutputBox').textContent,/Grounded summary/);
assert.match(element('studyOutputBox').textContent,/01:05/);
vm.runInContext(`renderStudyMode(${JSON.stringify({concepts:[{name:'Safe <term>',explanation:'A clear explanation',timestamp:12}],definitions:[],formulas:[],coverage:{guide_cues:1,captured_cues:1}})},'FLASHCARDS')`,context);
assert.match(element('studyOutputBox').innerHTML,/&lt;term&gt;/,'model text must be escaped before it enters study-card HTML');
vm.runInContext(`renderStudyMode(${JSON.stringify({practice_questions:[{question:'Why?',answer:'Because.',timestamp:12}],coverage:{guide_cues:1,captured_cues:1}})},'QUIZ')`,context);
assert.match(element('studyOutputBox').innerHTML,/Because\./);
element('apiBase').value='https://tutor.example';
vm.runInContext(`persistTutorToken('opaque-jwt','https://tutor.example')`,context);
assert.equal(storage.has('pw_tutor_token'),false,'Android should keep JWTs in the native encrypted vault, not WebView storage');
assert.equal(vm.runInContext('restoreTutorToken()',context),'opaque-jwt');
vm.runInContext('clearTutorToken()',context);
assert.equal(vaultedToken,'');

console.log('Tutor UI tests passed: course-switch reset, backed study modes, escaped model output, and native token-vault integration.');
