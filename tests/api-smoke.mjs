import assert from 'node:assert/strict';
const base=process.env.TEST_BASE_URL||'http://127.0.0.1:4173';
const headers={'oai-authenticated-user-id':'smoke-user-a','oai-authenticated-user-email':'smoke-a@example.invalid',Origin:base};
async function request(path,method='GET',body,user='a'){
 const h={...headers,'oai-authenticated-user-id':`smoke-user-${user}`};
 if(body&&!(body instanceof FormData)){h['Content-Type']='application/json';body=JSON.stringify(body);}
 const r=await fetch(base+path,{method,headers:h,body});return {status:r.status,data:await r.json()};
}
assert.equal((await fetch(base+'/api/entries')).status,401);
const id=crypto.randomUUID(), date=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Seoul'}).format(new Date());
const f=new FormData();f.set('entry',JSON.stringify({id,date,transcript:'검증용 기록입니다. 산책하며 하루를 돌아봤어요.',mode:'listen',keepAudio:false}));
try{
 const created=await request('/api/entries','POST',f);assert.equal(created.status,201,JSON.stringify(created));
 assert.equal((await request('/api/entries/'+id,'GET',undefined,'b')).status,404);
 assert.equal((await request('/api/entries/'+id,'DELETE',undefined,'b')).status,404);
 const updated=await request('/api/entries/'+id,'PATCH',{title:'검증 기록',transcript:'수정한 검증용 원문',summary:'',revision:created.data.entry.revision});assert.equal(updated.status,200,JSON.stringify(updated));
 const stale=await request('/api/entries/'+id,'PATCH',{title:'오래된 수정',transcript:'이 내용은 저장되면 안 돼요',summary:'',revision:created.data.entry.revision});assert.equal(stale.status,409);
 assert.equal((await request('/api/entries/'+id+'/summarize','POST',{})).status,503);
 const attack=await fetch(base+'/api/entries/'+id,{method:'DELETE',headers:{...headers,Origin:'https://example.invalid'}});assert.equal(attack.status,403);
 const other=await request('/api/export','GET',undefined,'b');assert(!other.data.entries.some(e=>e.id===id));
 console.log('PASS: authentication, ownership, save, revision conflict, keyless AI state, CSRF, export isolation');
}finally{await request('/api/entries/'+id,'DELETE');}
