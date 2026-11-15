import test from 'node:test';
import assert from 'node:assert/strict';

test('server module exposes app and health route', async (t) => {
  process.env.NODE_ENV = 'test';
  process.env.JWT_SECRET = 'test-secret-with-more-than-32-characters';
  process.env.DATABASE_PATH = ':memory:';
  delete process.env.OPENAI_API_KEY;
  const { app, selectRelevantCaptions, evenlySampleCaptions } = await import('../src/server.js');
  const server = app.listen(0);
  await new Promise(resolve => server.once('listening', resolve));
  t.after(()=>new Promise(resolve=>server.close(resolve)));
  const { port } = server.address();
  const response = await fetch(`http://127.0.0.1:${port}/health`);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true });
  const register=await fetch(`http://127.0.0.1:${port}/api/auth/register`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:'learner@example.com',password:'a-long-test-password'})});
  assert.equal(register.status,201);
  const {token}=await register.json();
  const login=await fetch(`http://127.0.0.1:${port}/api/auth/login`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:'LEARNER@example.com',password:'a-long-test-password'})});
  assert.equal(login.status,200,'registered learners must be able to sign in');
  const loggedIn=await login.json();
  const currentUser=await fetch(`http://127.0.0.1:${port}/api/auth/me`,{headers:{Authorization:`Bearer ${loggedIn.token}`}});
  assert.deepEqual((await currentUser.json()).user.email,'learner@example.com');
  const wrongPassword=await fetch(`http://127.0.0.1:${port}/api/auth/login`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:'learner@example.com',password:'incorrect-long-password'})});
  assert.equal(wrongPassword.status,401);
  const captionUrl=`http://127.0.0.1:${port}/api/lessons/captions`;
  const captionHeaders={Authorization:`Bearer ${token}`,'Content-Type':'application/json'};
  const captions=await fetch(captionUrl,{method:'POST',headers:captionHeaders,body:JSON.stringify({courseId:'course_alpha',lessonId:'lesson_987',title:'Caption lesson',source:'visible-html5-text-track',segments:[{start:12,end:15,text:'Visible authorized caption.'}]})});
  assert.equal(captions.status,200);
  const captured=await captions.json();
  assert.equal(captured.captured,1);
  const firstLectureId=captured.id;
  const repeated=await fetch(captionUrl,{method:'POST',headers:captionHeaders,body:JSON.stringify({courseId:'course_alpha',lessonId:'lesson_987',title:'Caption lesson',source:'visible-html5-text-track',segments:[{start:12,end:15,text:'Visible authorized caption.'},{start:18,end:20,text:'Second caption.'}]})});
  const repeatedBody=await repeated.json();
  assert.equal(repeatedBody.id,firstLectureId);
  assert.equal(repeatedBody.captured,2,'retries must be idempotent while new cues are appended');
  const largeBatches=[];
  for(let i=0;i<10020;i++)largeBatches.push({start:30+i,end:31+i,text:`Long lecture cue ${i}.`});
  for(let offset=0;offset<largeBatches.length;offset+=100){
    const batchResponse=await fetch(captionUrl,{method:'POST',headers:captionHeaders,body:JSON.stringify({courseId:'course_alpha',lessonId:'lesson_987',title:'Caption lesson',source:'visible-html5-text-track',segments:largeBatches.slice(offset,offset+100)})});
    assert.equal(batchResponse.status,200);
    var longLecture=await batchResponse.json();
  }
  assert.equal(longLecture.captured,10022,'older caption cues must remain stored beyond the previous 10,000 cue cap');
  const otherCourse=await fetch(captionUrl,{method:'POST',headers:captionHeaders,body:JSON.stringify({courseId:'course_beta',lessonId:'lesson_987',title:'Other course lesson',source:'visible-html5-text-track',segments:[{start:12,end:13,text:'Different course.'}]})});
  assert.notEqual((await otherCourse.json()).id,firstLectureId,'same provider lesson id in another course must not share context');
  const invalidCue=await fetch(captionUrl,{method:'POST',headers:captionHeaders,body:JSON.stringify({courseId:'course_alpha',lessonId:'lesson_987',title:'Caption lesson',source:'visible-html5-text-track',segments:[{start:20,end:10,text:'Invalid timestamps'}]})});
  assert.equal(invalidCue.status,400);
  const rejected=await fetch(captionUrl,{method:'POST',headers:captionHeaders,body:JSON.stringify({courseId:'course_alpha',lessonId:'lesson_987',title:'Caption lesson',source:'downloaded-media',segments:[{start:18,end:19,text:'Not allowed'}]})});
  assert.equal(rejected.status,400);
  const learnerLecture=await fetch(`http://127.0.0.1:${port}/api/lectures/${firstLectureId}`,{headers:{Authorization:`Bearer ${token}`}});
  const storedLecture=await learnerLecture.json();
  assert.equal(storedLecture.segments.length,300,'lecture detail endpoint returns a bounded recent transcript page');
  assert.equal(storedLecture.segmentCount,10022,'lecture detail still reports the full captured cue count');
  assert.equal(storedLecture.transcript,'','large caption text is not duplicated into each lecture detail response');
  const finalSegments=await fetch(`http://127.0.0.1:${port}/api/lectures/${firstLectureId}/segments?offset=10000&limit=50`,{headers:captionHeaders});
  const finalPage=await finalSegments.json();
  assert.equal(finalPage.total,10022);
  assert.equal(finalPage.segments.length,22,'long transcripts can be read in bounded pages');
  const invalidPage=await fetch(`http://127.0.0.1:${port}/api/lectures/${firstLectureId}/segments?offset=0&limit=501`,{headers:captionHeaders});
  assert.equal(invalidPage.status,400);
  assert.deepEqual(storedLecture.segments.map(x=>x.start),[...storedLecture.segments.map(x=>x.start)].sort((a,b)=>a-b));
  const retrieved=selectRelevantCaptions(storedLecture,'Long lecture cue 10019',3000);
  assert.match(retrieved.text,/\[167:29\] Long lecture cue 10019\./,'retrieval must search late lecture cues, not just the beginning');
  assert.ok(retrieved.includedSegments<retrieved.totalSegments,'retrieval reports when it selected only a subset');
  const search=await fetch(`http://127.0.0.1:${port}/api/lectures/${firstLectureId}/search?q=${encodeURIComponent('Long lecture cue 10019')}`,{headers:captionHeaders});
  assert.equal(search.status,200);
  assert.match((await search.json()).snippets,/\[167:29\] Long lecture cue 10019\./);
  const sampled=evenlySampleCaptions(storedLecture,5000);
  assert.ok(sampled.partial);
  assert.match(sampled.text,/\[167:29\] Long lecture cue 10019\./,'summary sampling must include late lecture material');
  const studyUnavailable=await fetch(`http://127.0.0.1:${port}/api/lectures/${firstLectureId}/study-guide`,{method:'POST',headers:captionHeaders,body:'{}'});
  assert.equal(studyUnavailable.status,503,'study guide must fail clearly when the server key is not configured');
  const chatUnavailable=await fetch(`http://127.0.0.1:${port}/api/lectures/${firstLectureId}/chat`,{method:'POST',headers:captionHeaders,body:JSON.stringify({question:'Long lecture cue 10019'})});
  assert.equal(chatUnavailable.status,503,'grounded chat must not pretend to work without a server key');
  const voiceUnavailable=await fetch(`http://127.0.0.1:${port}/api/realtime/session`,{method:'POST',headers:captionHeaders,body:JSON.stringify({lectureId:firstLectureId})});
  assert.equal(voiceUnavailable.status,503,'Realtime credentials must be issued only by a configured backend');
  const originalFetch=globalThis.fetch;
  process.env.OPENAI_API_KEY='test-only-placeholder-key';
  globalThis.fetch=async(url,options)=>{
    if(!String(url).includes('api.openai.com'))return originalFetch(url,options);
    assert.match(String(url),/api\.openai\.com/);
    const body=JSON.parse(options.body);
    if(String(url).includes('/client_secrets')){
      assert.equal(body.session.audio.input.transcription.model,'gpt-transcribe');
      assert.equal(body.session.audio.input.turn_detection.interrupt_response,true);
      assert.equal(body.session.audio.output.voice,'marin');
      return new Response(JSON.stringify({value:'ephemeral-test-token'}),{status:200,headers:{'content-type':'application/json'}});
    }
    if(body.stream){
      return new Response('data: {"choices":[{"delta":{"content":"Grounded teaching response [00:12]."}}]}\n\ndata: [DONE]\n\n',{status:200,headers:{'content-type':'text/event-stream'}});
    }
    const content=body.messages?.[0]?.content?.includes('Suggest three')?{questions:['Why does this step work?','Can you show a related example?','What is a common mistake?']}:{summary:'A concise partial-caption summary.',chapters:[{start_seconds:12,title:'Visible section',summary:'Explains the first idea.'}],concepts:[{name:'Visible concept',explanation:'Step by step.',timestamp:12}],definitions:[{term:'Term',definition:'Meaning.',timestamp:12}],formulas:[],revision_notes:['Review the central idea.'],practice_questions:[{question:'What is the key idea?',answer:'The visible caption states it.',timestamp:12}]};
    return new Response(JSON.stringify({id:'test-guide',object:'chat.completion',created:1,model:'test',choices:[{index:0,message:{role:'assistant',content:JSON.stringify(content)},finish_reason:'stop'}],usage:{prompt_tokens:1,completion_tokens:1,total_tokens:2}}),{status:200,headers:{'content-type':'application/json'}});
  };
  try {
    const chat=await fetch(`http://127.0.0.1:${port}/api/lectures/${firstLectureId}/chat`,{method:'POST',headers:captionHeaders,body:JSON.stringify({question:'Explain Long lecture cue 10019 step by step'})});
    assert.equal(chat.status,200);
    assert.match(await chat.text(),/Grounded teaching response/);
    const savedHistory=await fetch(`http://127.0.0.1:${port}/api/lectures/${firstLectureId}/history`,{headers:captionHeaders});
    assert.deepEqual((await savedHistory.json()).slice(-2).map(x=>x.role),['user','assistant']);
    const followups=await fetch(`http://127.0.0.1:${port}/api/lectures/${firstLectureId}/followups`,{method:'POST',headers:captionHeaders,body:'{}'});
    assert.deepEqual((await followups.json()).questions,['Why does this step work?','Can you show a related example?','What is a common mistake?']);
    const guideResponse=await fetch(`http://127.0.0.1:${port}/api/lectures/${firstLectureId}/study-guide`,{method:'POST',headers:captionHeaders,body:'{}'});
    assert.equal(guideResponse.status,200);
    assert.equal((await guideResponse.json()).practice_questions.length,1);
    const voiceResponse=await fetch(`http://127.0.0.1:${port}/api/realtime/session`,{method:'POST',headers:captionHeaders,body:JSON.stringify({lectureId:firstLectureId})});
    assert.equal(voiceResponse.status,200);
    assert.equal((await voiceResponse.json()).value,'ephemeral-test-token');
  } finally {
    globalThis.fetch=originalFetch;
    delete process.env.OPENAI_API_KEY;
  }
  const created=await fetch(`http://127.0.0.1:${port}/api/lectures/transcript`,{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify({title:'Test lecture',transcript:'An authorized transcript for testing the grounded tutor.'})});
  assert.equal(created.status,201);
  const lecture=await created.json();
  const owned=await fetch(`http://127.0.0.1:${port}/api/lectures/${lecture.id}`,{headers:{Authorization:`Bearer ${token}`}});
  assert.equal(owned.status,200);
  assert.match((await owned.json()).transcript,/authorized transcript/);
  const anonymous=await fetch(`http://127.0.0.1:${port}/api/lectures/${lecture.id}`);
  assert.equal(anonymous.status,401);
  const second=await fetch(`http://127.0.0.1:${port}/api/auth/register`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:'other@example.com',password:'another-long-test-password'})});
  const otherToken=(await second.json()).token;
  const crossAccount=await fetch(`http://127.0.0.1:${port}/api/lectures/${lecture.id}`,{headers:{Authorization:`Bearer ${otherToken}`}});
  assert.equal(crossAccount.status,404);
  const privateSearch=await fetch(`http://127.0.0.1:${port}/api/lectures/${firstLectureId}/search?q=caption`,{headers:{Authorization:`Bearer ${otherToken}`}});
  assert.equal(privateSearch.status,404,'caption search must remain learner-scoped');
});
