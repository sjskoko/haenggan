import webpush from "web-push";
import { env } from "cloudflare:workers";
import { ApiError, db } from "./server";
import { dateKey } from "./domain";
export function validPushEndpoint(value:string){try{const url=new URL(value);return url.protocol==="https:"&&!url.username&&!url.password&&!url.port&&["fcm.googleapis.com","updates.push.services.mozilla.com","web.push.apple.com"].some(host=>url.hostname===host||url.hostname.endsWith("."+host));}catch{return false;}}
export async function sendPush(subscription:webpush.PushSubscription,origin:string,test=false){
  if(!env.VAPID_PUBLIC_KEY||!env.VAPID_PRIVATE_KEY)throw new ApiError(503,"알림 연결을 준비하고 있어요.");
  if(!validPushEndpoint(subscription.endpoint))throw new ApiError(400,"지원하지 않는 알림 주소예요.");
  const details=webpush.generateRequestDetails(subscription,JSON.stringify({title:test?"행간 · 알림이 도착했어요":"행간 · 오늘을 돌아볼 시간",body:test?"이 알림을 누르면 일기 쓰기가 열려요.":"오늘 하루에 남겨두고 싶은 이야기가 있나요?",url:"/?write=1",tag:test?"haenggan-test":"haenggan-daily"}),{vapidDetails:{subject:origin,publicKey:env.VAPID_PUBLIC_KEY,privateKey:env.VAPID_PRIVATE_KEY},TTL:3600,urgency:"high"});
  return fetch(details.endpoint,{method:details.method,headers:details.headers as Record<string,string>,body:details.body as unknown as BodyInit,redirect:"error",signal:AbortSignal.timeout(15000)});
}
export async function dispatchReminders(origin:string){
  const today=dateKey();const rows=await db().prepare("SELECT id,user_id,subscription FROM subscriptions WHERE enabled=1 AND (last_sent_date IS NULL OR last_sent_date<>?) LIMIT 100").bind(today).all<{id:string;user_id:string;subscription:string}>();
  let sent=0,skipped=0,failed=0;
  for(const row of rows.results){
    const written=await db().prepare("SELECT id FROM entries WHERE user_id=? AND date=? LIMIT 1").bind(row.user_id,today).first();
    if(written){skipped++;continue;}
    const claim=await db().prepare("UPDATE subscriptions SET last_sent_date=? WHERE id=? AND (last_sent_date IS NULL OR last_sent_date<>?) RETURNING id").bind(today,row.id,today).first();
    if(!claim)continue;
    try{const response=await sendPush(JSON.parse(row.subscription),origin);if(response.ok)sent++;else if([404,410].includes(response.status))await db().prepare("DELETE FROM subscriptions WHERE id=?").bind(row.id).run();else throw new Error("Push unavailable");}
    catch{failed++;await db().prepare("UPDATE subscriptions SET last_sent_date=NULL WHERE id=? AND last_sent_date=?").bind(row.id,today).run();}
  }
  return {date:today,sent,skipped,failed};
}
