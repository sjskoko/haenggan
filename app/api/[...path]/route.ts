import { env } from "cloudflare:workers";
import { z } from "zod";
import { authorize,db,bucket,json,ApiError,aiAllowance,askAI,objectSchema,stringSchema } from "@/lib/server";
import { dateKey,entryInput,titleFromText,validDate,weekStart,addDays,sourceSignature,validateEvidence,type Entry,type Report } from "@/lib/domain";
import { sendPush,dispatchReminders,validPushEndpoint } from "@/lib/push";
export const dynamic="force-dynamic";
type Row={id:string;user_id:string;date:string;title:string;transcript:string;summary:string;summary_origin:string;mode:string;mood:string|null;audio_key:string|null;duration:number;revision:number;created_at:string;updated_at:string};
function entry(r:Row):Entry{return {id:r.id,date:r.date,title:r.title,transcript:r.transcript,summary:r.summary,summaryOrigin:r.summary_origin,mode:r.mode,mood:r.mood,hasAudio:!!r.audio_key,duration:r.duration,revision:r.revision,createdAt:r.created_at,updatedAt:r.updated_at};}
async function getEntry(id:string,user:string){const r=await db().prepare("SELECT * FROM entries WHERE id=? AND user_id=?").bind(id,user).first<Row>();if(!r)throw new ApiError(404,"기록을 찾을 수 없어요.");return r;}
async function getWeek(start:string,user:string){return (await db().prepare("SELECT * FROM entries WHERE user_id=? AND date>=? AND date<=? ORDER BY date ASC,created_at ASC").bind(user,start,addDays(start,6)).all<Row>()).results;}
async function body(req:Request){const raw=await req.text();if(raw.length>180000)throw new ApiError(413,"입력 내용이 너무 길어요.");try{return JSON.parse(raw);}catch{throw new ApiError(400,"내용을 다시 확인해 주세요.");}}
async function form(req:Request){if(Number(req.headers.get("content-length"))>21_000_000)throw new ApiError(413,"녹음은 20MB 이내로 남겨주세요.");const bytes=await req.arrayBuffer();if(bytes.byteLength>21_000_000)throw new ApiError(413,"녹음은 20MB 이내로 남겨주세요.");return new Response(bytes,{headers:{"Content-Type":req.headers.get("content-type")||""}}).formData();}
function validateAudio(file:File){if(file.size>20_000_000)throw new ApiError(413,"녹음은 20MB 이내로 남겨주세요.");if(!["audio/webm","audio/mp4","audio/mpeg","audio/wav","audio/ogg","video/webm"].includes(file.type.split(";")[0]))throw new ApiError(400,"이 음성 파일 형식을 읽을 수 없어요.");}
const evidenceSchema=objectSchema({id:stringSchema,quote:stringSchema});
const reportSchema=objectSchema({title:stringSchema,sections:{type:"array",items:objectSchema({heading:stringSchema,body:stringSchema,evidence:{type:"array",items:evidenceSchema}})},nextStep:stringSchema});
async function handle(req:Request,context:{params:Promise<{path:string[]}>}){
 try{
  const {path}=await context.params;const route=path.join("/");const method=req.method;
  if(route==="reminders/dispatch"&&method==="POST"){
    // Only enable on an owner-private Sites deployment. Sites dispatch authenticates unattended callers.
    if(env.PRIVATE_SCHEDULE_ENABLED!=="1")throw new ApiError(403,"예약 실행이 연결되지 않았어요.");
    const origin=req.headers.get("origin");if(origin&&origin!==new URL(req.url).origin)throw new ApiError(403,"허용되지 않는 요청이에요.");
    const data=await body(req);
    if(data.dryRun===true){const count=await db().prepare("SELECT count(*) AS count FROM subscriptions WHERE enabled=1").first();return json({ok:true,dryRun:true,...count});}
    const time=new Intl.DateTimeFormat("en-GB",{timeZone:"Asia/Seoul",hour:"2-digit",minute:"2-digit",hourCycle:"h23"}).format(new Date());
    if(time<"23:30"||time>"23:59")return json({ok:true,skipped:"outside_reminder_window"});
    return json(await dispatchReminders(new URL(req.url).origin));
  }
  const user=await authorize(req);
  if(route==="meta"&&method==="GET")return json({userId:user,aiEnabled:!!env.OPENAI_API_KEY,pushPublicKey:env.VAPID_PUBLIC_KEY||null,scheduledPushEnabled:env.PRIVATE_SCHEDULE_ENABLED==="1"});
  if(route==="entries"&&method==="GET"){
    const url=new URL(req.url);const q=(url.searchParams.get("q")||"").slice(0,200);const before=url.searchParams.get("before")||"9999";
    const result=await db().prepare("SELECT * FROM entries WHERE user_id=? AND created_at<? AND (?='' OR instr(title,?)>0 OR instr(transcript,?)>0 OR instr(summary,?)>0) ORDER BY created_at DESC LIMIT 101").bind(user,before,q,q,q,q).all<Row>();
    return json({entries:result.results.slice(0,100).map(entry),nextCursor:result.results.length>100?result.results[99].created_at:null});
  }
  if(route==="entries"&&method==="POST"){
    const data=await form(req);let input:unknown;try{input=JSON.parse(String(data.get("entry")));}catch{throw new ApiError(400,"일기를 다시 확인해 주세요.");}
    const v=entryInput.parse(input);if(v.date>dateKey())throw new ApiError(400,"미래 날짜의 일기는 아직 남길 수 없어요.");
    const existing=await db().prepare("SELECT * FROM entries WHERE id=? AND user_id=?").bind(v.id,user).first<Row>();if(existing)return json({entry:entry(existing)});
    const audio=data.get("audio");const file=audio instanceof File&&audio.size?audio:null;if(file)validateAudio(file);
    if(!v.transcript&&!file)throw new ApiError(400,"글이나 목소리로 오늘을 남겨주세요.");
    if(!v.transcript&&!v.keepAudio)throw new ApiError(400,"글이 없는 일기는 원음 보관을 켜 주세요.");
    const key=file&&v.keepAudio?`${user}/${v.id}`:null;
    if(key&&file)await bucket().put(key,await file.arrayBuffer(),{httpMetadata:{contentType:file.type}});
    const now=new Date().toISOString();
    try{await db().prepare("INSERT INTO entries(id,user_id,date,title,transcript,mode,mood,audio_key,duration,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)").bind(v.id,user,v.date,v.title||titleFromText(v.transcript),v.transcript,v.mode,v.mood,key,v.duration,now,now).run();}
    catch(error){const duplicate=await db().prepare("SELECT * FROM entries WHERE id=? AND user_id=?").bind(v.id,user).first<Row>();if(duplicate)return json({entry:entry(duplicate)});if(key)await bucket().delete(key);throw error;}
    return json({entry:entry(await getEntry(v.id,user))},201);
  }
  if(path[0]==="entries"&&path[1]){
    const r=await getEntry(path[1],user);
    if(path.length===2&&method==="GET")return json({entry:entry(r)});
    if(path.length===2&&method==="DELETE"){
      // Delete the blob first so retrying can cleanly finish a partially completed operation.
      if(r.audio_key)await bucket().delete(r.audio_key);
      await db().batch([db().prepare("DELETE FROM entries WHERE id=? AND user_id=?").bind(r.id,user),db().prepare("DELETE FROM weeks WHERE user_id=? AND week_start=?").bind(user,weekStart(r.date))]);return json({ok:true});
    }
    if(path.length===2&&method==="PATCH"){
      const v=z.object({title:z.string().trim().min(1).max(80),transcript:z.string().trim().max(16000),summary:z.string().trim().max(8000),revision:z.number().int().positive()}).parse(await body(req));
      if(!v.transcript&&!r.audio_key)throw new ApiError(400,"일기 내용은 비워둘 수 없어요.");
      const changed=v.transcript!==r.transcript;const summary=changed&&v.summary===r.summary?"":v.summary;const origin=summary?(v.summary!==r.summary?"edited":r.summary_origin):"none";
      const updated=await db().prepare("UPDATE entries SET title=?,transcript=?,summary=?,summary_origin=?,revision=revision+1,updated_at=? WHERE id=? AND user_id=? AND revision=? RETURNING id").bind(v.title,v.transcript,summary,origin,new Date().toISOString(),r.id,user,v.revision).first();
      if(!updated)throw new ApiError(409,"다른 화면에서 기록이 바뀌었어요. 최신 기록을 다시 열어주세요.");return json({entry:entry(await getEntry(r.id,user))});
    }
    if(path[2]==="audio"&&method==="GET"){
      if(!r.audio_key)throw new ApiError(404,"보관한 원음이 없어요.");const obj=await bucket().get(r.audio_key);if(!obj)throw new ApiError(404,"원음을 찾을 수 없어요.");return new Response(obj.body,{headers:{"Content-Type":obj.httpMetadata?.contentType||"audio/webm","Cache-Control":"private, no-store","Content-Length":String(obj.size),"X-Content-Type-Options":"nosniff"}});
    }
    if(path[2]==="summarize"&&method==="POST"){
      if(!r.transcript)throw new ApiError(400,"받아쓴 내용이나 일기 글이 필요해요.");await aiAllowance(user);
      const result=await askAI(" 사용자의 일기를 1인칭으로 3~6문장 정리한다. 사용자의 어투와 구체적인 장면을 살리고 조언은 넣지 않는다. 제목은 25자 이내. 없는 감정을 추가하지 않는다.",{date:r.date,transcript:r.transcript},objectSchema({title:stringSchema,summary:stringSchema}));
      const parsed=z.object({title:z.string().min(1).max(80),summary:z.string().min(1).max(8000)}).parse(result);
      const updated=await db().prepare("UPDATE entries SET title=?,summary=?,summary_origin='ai',revision=revision+1,updated_at=? WHERE id=? AND user_id=? AND revision=? RETURNING id").bind(parsed.title,parsed.summary,new Date().toISOString(),r.id,user,r.revision).first();if(!updated)throw new ApiError(409,"정리하는 동안 원문이 바뀌었어요. 다시 정리해 주세요.");return json({entry:entry(await getEntry(r.id,user))});
    }
  }
  if(route==="question"&&method==="POST"){
    const v=z.object({transcript:z.string().trim().min(10).max(16000),previous:z.array(z.string().max(500)).max(5)}).parse(await body(req));await aiAllowance(user);
    return json(await askAI(" 사용자가 이야기한 구체적인 장면 중 더 듣고 싶은 것 하나만 짧게 질문한다. 이미 한 질문은 피한다. 한번에 질문 하나만 한다. 분석이나 조언이나 상투적인 공감을 붙이지 않는다. 충분히 이야기했으면 question은 빈 문자열로 반환한다.",v,objectSchema({question:stringSchema})));
  }
  if(route==="transcribe"&&method==="POST"){
    const data=await form(req);const file=data.get("audio");if(!(file instanceof File)||!file.size)throw new ApiError(400,"녹음 파일이 필요해요.");validateAudio(file);await aiAllowance(user);
    const audio=new FormData();audio.set("file",file,file.name);audio.set("model",env.OPENAI_TRANSCRIBE_MODEL||"gpt-transcribe");audio.set("prompt","자연스러운 한국어 하루 일기입니다. 들리는 발화만 그대로 옮기고 침묵에는 내용을 만들어 넣지 마세요.");
    const res=await fetch("https://api.openai.com/v1/audio/transcriptions",{method:"POST",headers:{Authorization:`Bearer ${env.OPENAI_API_KEY}`},body:audio,signal:AbortSignal.timeout(90000)});if(!res.ok)throw new ApiError(502,"음성을 글로 옮기지 못했어요. 원음은 화면에 남아 있어요.");const result=await res.json() as {text?:string};return json({text:result.text||""});
  }
  if(path[0]==="weeks"&&path[1]){
    const start=path[1];if(!validDate(start)||weekStart(start)!==start)throw new ApiError(400,"주간 날짜를 확인해 주세요.");const rows=await getWeek(start,user);const signature=sourceSignature(rows);const saved=await db().prepare("SELECT * FROM weeks WHERE user_id=? AND week_start=?").bind(user,start).first<{report:string;source_signature:string;intention:string;updated_at:string}>();
    if(method==="GET")return json({entries:rows.map(entry),report:saved&&saved.source_signature===signature&&saved.report?JSON.parse(saved.report):null,intention:saved?.intention||"",stale:!!saved&&!!saved.report&&saved.source_signature!==signature});
    if(method==="PATCH"){const v=z.object({intention:z.string().trim().max(300)}).parse(await body(req));await db().prepare("INSERT INTO weeks(id,user_id,week_start,source_signature,report,intention,updated_at) VALUES(?,?,?,'','',?,?) ON CONFLICT(user_id,week_start) DO UPDATE SET intention=excluded.intention,updated_at=excluded.updated_at").bind(crypto.randomUUID(),user,start,v.intention,new Date().toISOString()).run();return json({ok:true});}
    if(method==="POST"){
      if(!rows.some(e=>e.transcript))throw new ApiError(400,"이 주에 글로 남긴 일기가 필요해요.");if(JSON.stringify(rows).length>100000)throw new ApiError(413,"이번 주의 기록이 많아 한 번에 정리할 수 없어요. 원문 목록에서 확인해 주세요.");await aiAllowance(user);
      const report=await askAI(" 한 주의 회고를 작성한다. sections는 '이번 주의 흐름','마음의 변화','힘이 된 순간' 세 개이며 각각 2~4문장이다. 모든 사실적 주장에 해당하는 evidence에 입력 일기 id와 실제 짧은 발언 quote를 정확하게 복사한다. 사용자가 직접 고친 summaryOrigin=edited인 정리는 우선 존중한다. 날짜순 변화를 살피되 하루뿐이면 변화나 반복 패턴을 추측하지 않는다. 기록이 없는 날은 모른다고 본다. nextStep에는 기록에서 나온 다음 주의 부담 없는 행동 또는 태도 한 가지를 제안한다. 성장이나 긍정적 결말을 억지로 붙이지 않는다.",{weekStart:start,entries:rows.map(entry)},reportSchema) as Report;
      const validated=z.object({title:z.string().max(100),sections:z.array(z.object({heading:z.string().max(50),body:z.string().max(4000),evidence:z.array(z.object({id:z.string(),quote:z.string().max(500)})).max(12)})).length(3),nextStep:z.string().max(1000)}).parse(report);
      const latest=await getWeek(start,user);if(sourceSignature(latest)!==signature)throw new ApiError(409,"정리하는 동안 일기가 바뀌었어요. 다시 시도해 주세요.");
      const clean=validateEvidence(validated,rows.map(entry));await db().prepare("INSERT INTO weeks(id,user_id,week_start,source_signature,report,intention,updated_at) VALUES(?,?,?,?,?,'',?) ON CONFLICT(user_id,week_start) DO UPDATE SET source_signature=excluded.source_signature,report=excluded.report,updated_at=excluded.updated_at").bind(crypto.randomUUID(),user,start,signature,JSON.stringify(clean),new Date().toISOString()).run();return json({report:clean});
    }
  }
  if(route==="export"&&method==="GET"){
    const result=await db().prepare("SELECT * FROM entries WHERE user_id=? ORDER BY date,created_at").bind(user).all<Row>();const reports=await db().prepare("SELECT week_start,report,intention,source_signature FROM weeks WHERE user_id=?").bind(user).all();return json({format:"haenggan-v1",exportedAt:new Date().toISOString(),entries:result.results.map(entry),weeks:reports.results});
  }
  if(route==="push"&&method==="POST"){
    const v=z.object({endpoint:z.string().url().refine(validPushEndpoint),keys:z.object({p256dh:z.string().min(40).max(200),auth:z.string().min(10).max(100)})}).parse(await body(req));
    const digest=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(user+v.endpoint));const id=Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,"0")).join("");
    await db().prepare("INSERT INTO subscriptions(id,user_id,subscription,enabled,created_at) VALUES(?,?,?,1,?) ON CONFLICT(id) DO UPDATE SET subscription=excluded.subscription,enabled=1").bind(id,user,JSON.stringify(v),new Date().toISOString()).run();return json({ok:true});
  }
  if(route==="push"&&method==="DELETE"){const v=z.object({endpoint:z.string().url()}).parse(await body(req));const rows=await db().prepare("SELECT id,subscription FROM subscriptions WHERE user_id=?").bind(user).all<{id:string;subscription:string}>();for(const r of rows.results)if(JSON.parse(r.subscription).endpoint===v.endpoint)await db().prepare("DELETE FROM subscriptions WHERE id=? AND user_id=?").bind(r.id,user).run();return json({ok:true});}
  if(route==="push/test"&&method==="POST"){
    const v=z.object({endpoint:z.string().url()}).parse(await body(req));const rows=await db().prepare("SELECT subscription FROM subscriptions WHERE user_id=? AND enabled=1").bind(user).all<{subscription:string}>();const found=rows.results.find(r=>JSON.parse(r.subscription).endpoint===v.endpoint);if(!found)throw new ApiError(404,"이 기기에서 알림을 먼저 켜 주세요.");const res=await sendPush(JSON.parse(found.subscription),new URL(req.url).origin,true);if(!res.ok)throw new ApiError(502,"알림을 보내지 못했어요. 알림을 다시 켜 주세요.");return json({ok:true});
  }
  throw new ApiError(404,"요청한 기능을 찾을 수 없어요.");
 }catch(error){if(error instanceof ApiError)return json({error:error.message},error.status);if(error instanceof z.ZodError)return json({error:"입력 내용을 다시 확인해 주세요."},400);return json({error:"잠시 연결이 불안정해요. 입력한 내용은 유지됩니다. 다시 시도해 주세요."},500);}
}
export const GET=handle;export const POST=handle;export const PATCH=handle;export const DELETE=handle;
