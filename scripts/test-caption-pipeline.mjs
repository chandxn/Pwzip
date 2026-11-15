import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require=createRequire(import.meta.url);
const captions=require('../app/src/main/assets/caption_pipeline.js');
let modeWrites=0;
const showing={kind:'captions',activeCues:[
  {startTime:18.25,endTime:21.75,text:'Second cue'},
  {startTime:12.125,endTime:14.5,text:'<b>First</b> cue'}
]};
Object.defineProperty(showing,'mode',{get:()=>"showing",set:()=>{modeWrites++;}});
const hidden={kind:'subtitles',mode:'disabled',activeCues:[{startTime:0,endTime:2,text:'Hidden cue'}]};
const nonCaption={kind:'metadata',mode:'showing',activeCues:[{startTime:0,endTime:2,text:'Metadata'}]};
const cues=captions.readVisibleCues({textTracks:[hidden,nonCaption,showing]});
assert.deepEqual(cues,[{start:18.25,end:21.75,text:'Second cue'},{start:12.125,end:14.5,text:'First cue'}]);
assert.equal(modeWrites,0,'caption capture must never change a player track mode');
assert.equal(captions.lessonKey('course-a','lesson-1')===captions.lessonKey('course-b','lesson-1'),false,'lesson identity must include course');
assert.deepEqual(captions.mergeUniqueCues([cues[0]],[cues[0],cues[1]]),[
  {start:12.125,end:14.5,text:'First cue'},
  {start:18.25,end:21.75,text:'Second cue'}
]);
assert.equal(captions.normalizeCue({start:5,end:4,text:'Bad time'}),null);
console.log('Caption pipeline tests passed: visible-only capture, exact timestamps, ordering, deduplication, and course-scoped identity.');
