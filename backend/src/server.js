import express from 'express';
import helmet from 'helmet';
import cors from 'cors';
import rateLimit from 'express-rate-limit';
import multer from 'multer';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { DatabaseSync } from 'node:sqlite';
import OpenAI from 'openai';
import { z } from 'zod';
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';

const dbPath = process.env.DATABASE_PATH === ':memory:' ? ':memory:' : resolve(process.env.DATABASE_PATH || './data/tutor.sqlite');
if (dbPath !== ':memory:') mkdirSync(dirname(dbPath), { recursive: true });
export const db = new DatabaseSync(dbPath);
db.exec('PRAGMA journal_mode=WAL');
db.exec(`
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,email TEXT UNIQUE NOT NULL,password_hash TEXT NOT NULL,created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS lectures(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,title TEXT NOT NULL,filename TEXT NOT NULL,transcript TEXT NOT NULL DEFAULT '',segments TEXT NOT NULL DEFAULT '[]',summary TEXT NOT NULL DEFAULT '',chapters TEXT NOT NULL DEFAULT '[]',created_at TEXT NOT NULL,provider_lesson_id TEXT,FOREIGN KEY(user_id) REFERENCES users(id));
CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,lecture_id TEXT NOT NULL,role TEXT NOT NULL,content TEXT NOT NULL,created_at TEXT NOT NULL,FOREIGN KEY(lecture_id) REFERENCES lectures(id));
CREATE INDEX IF NOT EXISTS messages_history ON messages(user_id,lecture_id,created_at);
`);

try { db.exec('ALTER TABLE lectures ADD COLUMN provider_lesson_id TEXT'); } catch (e) { if (!String(e.message).includes('duplicate column')) throw e; }
try { db.exec("ALTER TABLE lectures ADD COLUMN study_guide TEXT NOT NULL DEFAULT '{}'"); } catch (e) { if (!String(e.message).includes('duplicate column')) throw e; }
db.exec('CREATE UNIQUE INDEX IF NOT EXISTS lectures_provider_lesson ON lectures(user_id,provider_lesson_id) WHERE provider_lesson_id IS NOT NULL');

const app = express();
app.disable('x-powered-by');
if (process.env.TRUST_PROXY && process.env.TRUST_PROXY !== '0') app.set('trust proxy',Number(process.env.TRUST_PROXY)||1);
app.use(helmet({ crossOriginResourcePolicy: { policy: 'cross-origin' } }));
const allowedOrigins = (process.env.ALLOWED_ORIGINS || '').split(',').map(s => s.trim()).filter(Boolean);
app.use(cors({ origin(origin, done) { if (!origin || origin === 'null' || allowedOrigins.includes(origin)) return done(null, true); const error=new Error('Origin not allowed');error.status=403;done(error); }, allowedHeaders: ['Authorization','Content-Type'], methods: ['GET','POST','OPTIONS'] }));
app.use(express.json({ limit: '1mb' }));
app.use(rateLimit({ windowMs: 60_000, limit: process.env.NODE_ENV==='test'?1000:60, standardHeaders: 'draft-7', legacyHeaders: false }));
app.use((req,res,next)=>{req.requestId=randomUUID();res.setHeader('X-Request-Id',req.requestId);if(process.env.NODE_ENV!=='test'){const started=Date.now();res.on('finish',()=>console.info(JSON.stringify({event:'http_request',requestId:req.requestId,method:req.method,status:res.statusCode,durationMs:Date.now()-started})));}next();});
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: Math.min(25, Math.max(1, Number(process.env.MAX_UPLOAD_MB || 25))) * 1024 * 1024, files: 1 }, fileFilter(_req, file, cb) { const ok = /^(audio|video)\//.test(file.mimetype) || /\.(mp3|mp4|m4a|wav|webm|mpeg|mpga|ogg|flac)$/i.test(file.originalname); cb(ok ? null : new Error('Upload an audio or video lecture file.'), ok); } });
const emailSchema = z.string().email().max(254);
const authSchema = z.object({ email: emailSchema, password: z.string().min(10).max(200) });
const authLimiter=rateLimit({windowMs:15*60_000,limit:10,standardHeaders:'draft-7',legacyHeaders:false,message:{error:'Too many sign-in attempts. Try again later.'}});
const aiLimiter=rateLimit({windowMs:60_000,limit:20,standardHeaders:'draft-7',legacyHeaders:false,message:{error:'Tutor request limit reached. Try again in a minute.'}});
const now = () => new Date().toISOString();
const tokenSecret = process.env.JWT_SECRET;
function requireConfig() { if (!tokenSecret || tokenSecret.length < 32) throw Object.assign(new Error('JWT_SECRET must contain at least 32 characters.'), { status: 503 }); }
function auth(req, res, next) { try { requireConfig(); const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, ''); if (!token) return res.status(401).json({ error: 'Sign in required.' }); req.user = jwt.verify(token, tokenSecret); next(); } catch (e) { return res.status(e.status || 401).json({ error: e.status ? e.message : 'Invalid or expired sign-in.' }); } }
function openai() { if (!process.env.OPENAI_API_KEY) throw Object.assign(new Error('AI service is not configured on the server.'), { status: 503 }); return new OpenAI({ apiKey: process.env.OPENAI_API_KEY }); }
function ownedLecture(id, userId) { return db.prepare('SELECT * FROM lectures WHERE id=? AND user_id=?').get(id, userId); }
function segmentsAsText(segments) { return segments.map(s => `[${Math.floor(s.start / 60)}:${String(Math.floor(s.start % 60)).padStart(2,'0')}] ${s.text}`).join('\n'); }
function lectureSegments(lecture) {
  if(Array.isArray(lecture.segments)&&lecture.segments.length)return lecture.segments;
  try { const parsed=JSON.parse(lecture.segments||'[]'); if(Array.isArray(parsed)&&parsed.length)return parsed; } catch {}
  return String(lecture.transcript||'').split(/\r?\n/).map(line=>{const match=line.match(/^\[(\d+):(\d{2})\]\s*(.*)$/);return match?{start:Number(match[1])*60+Number(match[2]),end:Number(match[1])*60+Number(match[2]),text:match[3]}:null;}).filter(Boolean);
}
function selectRelevantCaptions(lecture,query,maxChars=50000) {
  const all=lectureSegments(lecture),terms=[...new Set(String(query||'').toLocaleLowerCase().match(/[\p{L}\p{N}]{2,}/gu)||[])];
  const termSet=new Set(terms); const scored=all.map((segment,index)=>{const words=String(segment.text||'').toLocaleLowerCase().match(/[\p{L}\p{N}]{2,}/gu)||[];const score=words.reduce((n,w)=>n+(termSet.has(w)?1:0),0);return {segment,index,score};});
  const relevant=scored.filter(x=>x.score>0).sort((a,b)=>b.score-a.score||a.index-b.index); const chosen=new Set();let used=0;
  for(const hit of relevant){for(let i=Math.max(0,hit.index-1);i<=Math.min(all.length-1,hit.index+1);i++){if(chosen.has(i))continue;const segment=all[i];const line=`[${Math.floor(segment.start/60)}:${String(Math.floor(segment.start%60)).padStart(2,'0')}] ${segment.text}`;if(used+line.length>maxChars)continue;chosen.add(i);used+=line.length+1;}}
  const output=[...chosen].sort((a,b)=>a-b).map(index=>{const segment=all[index];return `[${Math.floor(segment.start/60)}:${String(Math.floor(segment.start%60)).padStart(2,'0')}] ${segment.text}`;});
  return {text:output.join('\n'),totalSegments:all.length,includedSegments:output.length,partial:output.length<all.length};
}
function evenlySampleCaptions(lecture,maxChars=100000) {
  const all=lectureSegments(lecture); let chosen=all; let text=segmentsAsText(chosen);
  if(text.length>maxChars&&all.length){let count=Math.max(1,Math.floor(all.length*maxChars/text.length));do{chosen=Array.from({length:count},(_,i)=>all[Math.floor(i*(all.length-1)/Math.max(1,count-1))]);text=segmentsAsText(chosen);if(text.length<=maxChars||count===1)break;count=Math.max(1,Math.floor(count*.9));}while(true);}
  return {text,totalSegments:all.length,includedSegments:chosen.length,partial:chosen.length<all.length};
}

app.get('/health', (_req,res) => res.json({ ok: true }));
app.post('/api/auth/register',authLimiter, async (req,res,next) => { try { requireConfig(); const {email,password}=authSchema.parse(req.body); const id=randomUUID(); db.prepare('INSERT INTO users VALUES(?,?,?,?)').run(id,email.toLowerCase(),await bcrypt.hash(password,12),now()); res.status(201).json({ token:jwt.sign({sub:id},tokenSecret,{expiresIn:'30d'}), user:{id,email:email.toLowerCase()} }); } catch(e) { if (e.code==='SQLITE_CONSTRAINT_UNIQUE') return res.status(409).json({error:'Account already exists.'}); next(e); } });
app.post('/api/auth/login',authLimiter, async (req,res,next) => { try { requireConfig(); const {email,password}=authSchema.parse(req.body); const user=db.prepare('SELECT * FROM users WHERE email=?').get(email.toLowerCase()); if(!user || !(await bcrypt.compare(password,user.password))) return res.status(401).json({error:'Email or password is incorrect.'}); res.json({token:jwt.sign({sub:user.id},tokenSecret,{expiresIn:'30d'}),user:{id:user.id,email:user.email}}); } catch(e){ next(e); } });
app.get('/api/lectures',auth,(req,res)=>{ const rows=db.prepare('SELECT id,title,filename,summary,chapters,study_guide,created_at FROM lectures WHERE user_id=? ORDER BY created_at DESC').all(req.user.sub); res.json(rows.map(x=>({...x,chapters:JSON.parse(x.chapters),studyGuide:JSON.parse(x.study_guide||'{}')}))); });
app.get('/api/lectures/:id/search',auth,(req,res)=>{const lecture=ownedLecture(req.params.id,req.user.sub);if(!lecture)return res.status(404).json({error:'Lecture not found.'});const query=String(req.query.q||'').trim();if(!query||query.length>200)return res.status(400).json({error:'Search text must contain 1 to 200 characters.'});const result=selectRelevantCaptions(lecture,query,12000);res.json({snippets:result.text,included:result.includedSegments,total:result.totalSegments,coverage:'partial-visible-captions'});});
app.post('/api/lessons/captions',auth,(req,res)=>{
  const {lessonId,title,courseId='',source,segments}=req.body||{};
  if(source!=='visible-html5-text-track') return res.status(400).json({error:'Only captions already visible in the authorized player are accepted.'});
  const validId=value=>/^[A-Za-z0-9_-]{3,128}$/.test(String(value||''));
  if(!validId(lessonId)||(courseId&&!validId(courseId))||!String(title||'').trim()||String(title).length>200||!Array.isArray(segments)||segments.length>100) return res.status(400).json({error:'Invalid lesson caption batch.'});
  const incoming=[];
  for(const cue of segments){
    const start=Number(cue?.start),end=Number(cue?.end),text=String(cue?.text||'').replace(/<[^>]*>/g,' ').replace(/\s+/g,' ').trim();
    if(!Number.isFinite(start)||!Number.isFinite(end)||start<0||end<start||end>24*60*60||!text||text.length>1000) return res.status(400).json({error:'Caption cue has invalid timestamps or text.'});
    incoming.push({start,end,text});
  }
  if(!incoming.length)return res.status(400).json({error:'No visible caption cues in this batch.'});
  const providerLessonKey=courseId?`${courseId}/${lessonId}`:lessonId;
  let lecture=db.prepare('SELECT * FROM lectures WHERE user_id=? AND provider_lesson_id=?').get(req.user.sub,providerLessonKey);
  if(!lecture){
    const id=randomUUID();
    db.prepare('INSERT INTO lectures(id,user_id,title,filename,transcript,segments,created_at,provider_lesson_id) VALUES(?,?,?,?,?,?,?,?)').run(id,req.user.sub,String(title).trim(),'authorized visible player captions','', '[]',now(),providerLessonKey);
    lecture=ownedLecture(id,req.user.sub);
  }
  const prior=JSON.parse(lecture.segments||'[]');
  const byKey=new Map(prior.map(x=>[`${x.start}|${x.end}|${x.text}`,x]));
  for(const x of incoming)byKey.set(`${x.start}|${x.end}|${x.text}`,x);
  const merged=[...byKey.values()].sort((a,b)=>a.start-b.start||a.end-b.end||a.text.localeCompare(b.text));
  const transcript=segmentsAsText(merged);
  db.prepare('UPDATE lectures SET title=?,segments=?,transcript=? WHERE id=? AND user_id=?').run(String(title).trim(),JSON.stringify(merged),transcript,lecture.id,req.user.sub);
  res.json({id:lecture.id,title:String(title).trim(),captured:merged.length,added:merged.length-prior.length,lastTimestamp:merged.at(-1)?.start??null,source,coverage:'partial-visible-captions'});
});
app.post('/api/lectures/upload',auth,upload.single('lecture'),async(req,res,next)=>{ try { if(!req.file) return res.status(400).json({error:'Choose an audio or video file.'}); const title=String(req.body.title || req.file.originalname).slice(0,200); const ai=openai(); const file=new File([req.file.buffer],req.file.originalname,{type:req.file.mimetype}); const result=await ai.audio.transcriptions.create({file,model:'whisper-1',response_format:'verbose_json',timestamp_granularities:['segment']}); const segments=(result.segments||[]).map(s=>({start:Number(s.start)||0,end:Number(s.end)||0,text:String(s.text||'').trim()})).filter(s=>s.text); const transcript=segments.length?segmentsAsText(segments):String(result.text||''); const id=randomUUID(); db.prepare('INSERT INTO lectures(id,user_id,title,filename,transcript,segments,created_at) VALUES(?,?,?,?,?,?,?)').run(id,req.user.sub,title,req.file.originalname,transcript,JSON.stringify(segments),now()); res.status(201).json({id,title,transcript,segments}); }catch(e){next(e);} });
app.post('/api/lectures/:id/transcript',auth,(req,res)=>{ const title=String(req.body.title||'Uploaded transcript').slice(0,200); const transcript=String(req.body.transcript||'').trim(); if(transcript.length<20||transcript.length>500000) return res.status(400).json({error:'Transcript must be between 20 and 500,000 characters.'}); const id=randomUUID(); db.prepare('INSERT INTO lectures(id,user_id,title,filename,transcript,created_at) VALUES(?,?,?,?,?,?)').run(id,req.user.sub,title,'user-provided transcript',transcript,now()); res.status(201).json({id,title,transcript,segments:[]}); });
app.post('/api/lectures/transcript',auth,(req,res)=>{ const title=String(req.body.title||'Uploaded transcript').slice(0,200); const transcript=String(req.body.transcript||'').trim(); if(transcript.length<20||transcript.length>500000) return res.status(400).json({error:'Transcript must be between 20 and 500,000 characters.'}); const id=randomUUID(); db.prepare('INSERT INTO lectures(id,user_id,title,filename,transcript,created_at) VALUES(?,?,?,?,?,?)').run(id,req.user.sub,title,'user-provided transcript',transcript,now()); res.status(201).json({id,title,transcript,segments:[]}); });
app.get('/api/lectures/:id',auth,(req,res)=>{const l=ownedLecture(req.params.id,req.user.sub);if(!l)return res.status(404).json({error:'Lecture not found.'});res.json({...l,segments:JSON.parse(l.segments),chapters:JSON.parse(l.chapters),studyGuide:JSON.parse(l.study_guide||'{}')});});
app.post('/api/lectures/:id/study-guide',auth,aiLimiter,async(req,res,next)=>{
  try {
    const lecture=ownedLecture(req.params.id,req.user.sub);if(!lecture)return res.status(404).json({error:'Lecture not found.'});
    if(!lecture.transcript)return res.status(409).json({error:'This lesson has no visible captions yet.'});
    const source=evenlySampleCaptions(lecture,90000);const ai=openai();
    const result=await ai.chat.completions.create({model:process.env.OPENAI_TEXT_MODEL||'gpt-4o-mini',response_format:{type:'json_object'},messages:[
      {role:'system',content:'Act as a patient personal teacher. Use only the supplied timestamped caption excerpts. Return JSON with summary (string), chapters (array of {start_seconds,title,summary}), concepts (array of {name,explanation,timestamp}), definitions (array of {term,definition,timestamp}), formulas (array of {formula,explanation,timestamp}), revision_notes (array of strings), practice_questions (array of {question,answer,timestamp}). Cite only timestamps present in the excerpts. Do not invent lecture facts. Clearly mark the result as based on partial visible caption coverage; concepts and formulas may be absent if unsupported.'},
      {role:'user',content:`Captured cues: ${source.totalSegments}; cues included in this guide: ${source.includedSegments}; coverage: partial visible captions.
${source.text}`}
    ]});
    const doc=JSON.parse(result.choices[0]?.message?.content||'{}');
    const segments=lectureSegments(lecture);const validTimes=new Set(segments.map(x=>Math.floor(x.start)));
    const timed=items=>(Array.isArray(items)?items:[]).filter(x=>validTimes.has(Number(x.start_seconds??x.timestamp))).map(x=>({...x}));
    const guide={
      summary:String(doc.summary||''),
      chapters:(Array.isArray(doc.chapters)?doc.chapters:[]).filter(x=>validTimes.has(Number(x.start_seconds))).map(x=>({start_seconds:Number(x.start_seconds),title:String(x.title||'Chapter').slice(0,160),summary:String(x.summary||'').slice(0,1000)})),
      concepts:timed(doc.concepts).map(x=>({name:String(x.name||'').slice(0,160),explanation:String(x.explanation||'').slice(0,1200),timestamp:Number(x.timestamp)})),
      definitions:timed(doc.definitions).map(x=>({term:String(x.term||'').slice(0,160),definition:String(x.definition||'').slice(0,1200),timestamp:Number(x.timestamp)})),
      formulas:timed(doc.formulas).map(x=>({formula:String(x.formula||'').slice(0,300),explanation:String(x.explanation||'').slice(0,1200),timestamp:Number(x.timestamp)})),
      revision_notes:(Array.isArray(doc.revision_notes)?doc.revision_notes:[]).map(x=>String(x).slice(0,1200)).slice(0,40),
      practice_questions:timed(doc.practice_questions).map(x=>({question:String(x.question||'').slice(0,500),answer:String(x.answer||'').slice(0,1200),timestamp:Number(x.timestamp)})),
      coverage:{kind:'partial-visible-captions',captured_cues:source.totalSegments,guide_cues:source.includedSegments}
    };
    db.prepare('UPDATE lectures SET summary=?,chapters=?,study_guide=? WHERE id=? AND user_id=?').run(guide.summary,JSON.stringify(guide.chapters),JSON.stringify(guide),lecture.id,req.user.sub);
    res.json(guide);
  } catch(e){next(e);}
});
app.get('/api/lectures/:id/history',auth,(req,res)=>{if(!ownedLecture(req.params.id,req.user.sub))return res.status(404).json({error:'Lecture not found.'});res.json(db.prepare('SELECT role,content,created_at FROM messages WHERE user_id=? AND lecture_id=? ORDER BY created_at').all(req.user.sub,req.params.id));});
app.post('/api/lectures/:id/voice-message',auth,(req,res)=>{if(!ownedLecture(req.params.id,req.user.sub))return res.status(404).json({error:'Lecture not found.'});const role=req.body.role;const content=String(req.body.content||'').trim();if(!['user','assistant'].includes(role)||!content||content.length>4000)return res.status(400).json({error:'Voice message is invalid.'});db.prepare('INSERT INTO messages VALUES(?,?,?,?,?,?)').run(randomUUID(),req.user.sub,req.params.id,role,content,now());res.status(201).json({ok:true});});
app.post('/api/lectures/:id/chat',auth,aiLimiter,async(req,res,next)=>{
  try {
    const lecture=ownedLecture(req.params.id,req.user.sub);if(!lecture)return res.status(404).json({error:'Lecture not found.'});
    const question=String(req.body.question||'').trim();if(!question||question.length>4000)return res.status(400).json({error:'Question must be between 1 and 4,000 characters.'});
    const history=db.prepare('SELECT role,content FROM messages WHERE user_id=? AND lecture_id=? ORDER BY created_at DESC LIMIT 20').all(req.user.sub,lecture.id).reverse();
    const context=selectRelevantCaptions(lecture,[question,...history.slice(-6).map(x=>x.content)].join(' '),50000);
    if(!context.text)return res.status(409).json({error:'I could not find matching evidence in the visible captions captured for this lesson. Try a lecture-specific term or add more caption coverage.'});
    const ai=openai();const stream=await ai.chat.completions.create({model:process.env.OPENAI_TEXT_MODEL||'gpt-4o-mini',stream:true,messages:[
      {role:'system',content:`You are a patient personal teacher. Explain difficult ideas step by step in simple language. Reply in the learner's language (Hindi, Hinglish, or English). Answer using the supplied timestamped excerpts from authorized captions only. For claims supported by the lesson, cite exact [MM:SS] labels present below. If the excerpts do not establish an answer, say the lecture context is insufficient; you may then give a clearly labeled general explanation, example, or practice question. Distinguish lecture evidence from general teaching. Never claim full lecture coverage: only ${context.includedSegments} of ${context.totalSegments} captured cues are included in this retrieved context, and the capture itself covers only sections played with official captions visible. Never imply access to protected media or video visuals.
Relevant timestamped excerpts:
${context.text}`},
      ...history,{role:'user',content:question}
    ]});
    res.setHeader('Content-Type','text/event-stream');res.setHeader('Cache-Control','no-cache, no-transform');res.setHeader('Connection','keep-alive');res.flushHeaders();let answer='';
    for await(const part of stream){const token=part.choices[0]?.delta?.content||'';if(token){answer+=token;res.write(`data: ${JSON.stringify({delta:token})}\n\n`);}}
    db.prepare('INSERT INTO messages VALUES(?,?,?,?,?,?)').run(randomUUID(),req.user.sub,lecture.id,'user',question,now());db.prepare('INSERT INTO messages VALUES(?,?,?,?,?,?)').run(randomUUID(),req.user.sub,lecture.id,'assistant',answer,now());res.write('data: [DONE]\n\n');res.end();
  }catch(e){if(res.headersSent){res.write(`event: error\ndata: ${JSON.stringify({error:'Tutor response failed.'})}\n\n`);res.end();}else next(e);}
});
app.post('/api/realtime/session',auth,aiLimiter,async(req,res,next)=>{
  try{
    const lecture=ownedLecture(req.body.lectureId,req.user.sub);if(!lecture)return res.status(404).json({error:'Lecture not found.'});
    if(!lecture.transcript)return res.status(409).json({error:'Play this lesson with official captions visible before starting voice tutoring.'});
    if(!process.env.OPENAI_API_KEY)throw Object.assign(new Error('AI service is not configured on the server.'),{status:503});
    const history=db.prepare('SELECT role,content FROM messages WHERE user_id=? AND lecture_id=? ORDER BY created_at DESC LIMIT 12').all(req.user.sub,lecture.id).reverse();
    const context=evenlySampleCaptions(lecture,30000);
    const safetyId=createHash('sha256').update(String(req.user.sub)).digest('hex');
    const response=await fetch('https://api.openai.com/v1/realtime/client_secrets',{method:'POST',headers:{Authorization:`Bearer ${process.env.OPENAI_API_KEY}`,'Content-Type':'application/json','OpenAI-Safety-Identifier':safetyId},body:JSON.stringify({session:{type:'realtime',model:process.env.OPENAI_REALTIME_MODEL||'gpt-realtime-2.1',instructions:`You are a patient personal teacher. Speak naturally in the learner’s language (Hindi, Hinglish, or English), teach step by step, and preserve the learner’s conversation context. Use only these evenly sampled official caption cues visibly captured during playback for lecture-grounded facts; cite exact timestamp labels. The capture and context sample are partial and do not include unseen sections or video visuals. If the excerpt does not support an answer, say so, then clearly label any general explanation. Context sample: ${context.includedSegments} of ${context.totalSegments} captured cues.
${context.text}
Prior conversation:
${history.map(m=>`${m.role}: ${m.content}`).join('\n')}`,audio:{input:{turn_detection:{type:'semantic_vad',create_response:true,interrupt_response:true}},output:{voice:'marin'}}}})});
    const body=await response.json();if(!response.ok)throw Object.assign(new Error(body.error?.message||'Realtime session could not be created.'),{status:response.status});res.setHeader('Cache-Control','no-store');res.json(body);
  }catch(e){next(e);}
});

app.use((err,req,res,next)=>{
  if(res.headersSent)return next(err);
  if(err instanceof z.ZodError)return res.status(400).json({error:'Invalid request.',details:err.issues.map(i=>i.message)});
  if(err instanceof multer.MulterError)return res.status(413).json({error:'Upload is too large.'});
  const status=err.status||500;
  if(status>=500)console.error(JSON.stringify({event:'request_error',requestId:req.requestId,status,code:err.code||err.name||'Error'}));
  res.status(status).json({error:status>=500?'Tutor service error.':err.message});
});
export { app, selectRelevantCaptions, evenlySampleCaptions };
if (process.env.NODE_ENV !== 'test') { const port=Number(process.env.PORT||8787); const server=app.listen(port,()=>console.info(JSON.stringify({event:'server_started',port}))); const shutdown=()=>server.close(()=>{db.close();process.exit(0);}); process.once('SIGTERM',shutdown);process.once('SIGINT',shutdown); }
