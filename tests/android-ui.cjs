// Run with NODE_PATH pointing to a directory containing jsdom.
const {JSDOM}=require('jsdom');
const fs=require('node:fs');const assert=require('node:assert/strict');const path=require('node:path');
const assets=path.join(__dirname,'../mobile/android/app/src/main/assets');
const dom=new JSDOM(fs.readFileSync(path.join(assets,'index.html'),'utf8'),{runScripts:'outside-only',url:'https://appassets.androidplatform.net/index.html'});
const w=dom.window;const calls=[];let failRefine=false;const entry={id:'test',date:'2026-11-09',text:'오늘은 산책을 했다',title:'산책',revision:1};
const mock={activeAccount:'local',accounts:[{id:'local',sharing:true}],entries:[entry],draft:'',today:'2026-11-10',suggestedDate:'2026-11-09',onDeviceSpeech:true};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true};w.HTMLDialogElement.prototype.close=function(){this.open=false};
w.Haenggan={call(id,method,raw){const data=JSON.parse(raw);calls.push({method,data});let result=true,error=null;
 if(method==='state')result=mock;
 if(method==='refine'){result='오늘 산책을 했다.';if(failRefine)error='사용 한도에 도달했어요.';}
 if(method==='acceptRefinement'){entry.refined=data.text;entry.revision++;result=entry;}
 if(method==='speech')w.setTimeout(()=>w.nativeEvent('listening',{}),0);
 if(method==='stopSpeech')w.setTimeout(()=>w.nativeEvent('speechStopped',{}),0);
 w.setTimeout(()=>w.nativeReply({id,data:result,error}),0);
}};
w.eval(fs.readFileSync(path.join(assets,'app.js'),'utf8'));
const wait=ms=>new Promise(r=>setTimeout(r,ms));const click=s=>w.document.querySelector(s).click();
(async()=>{await wait(20);click('#voice');await wait(20);
w.nativeEvent('speechPartial',{text:'오늘은 산책을 했다'});click('#record');await wait(450);
assert.equal(w.document.querySelector('#text').value,'오늘은 산책을 했다');assert.equal(w.document.querySelector('#entry-date').value,'2026-11-09');
click('#record');await wait(20);w.nativeEvent('speechResult',{text:'마음이 편해졌다'});w.nativeEvent('speechStopped',{});await wait(450);
assert.equal(w.document.querySelector('#text').value,'오늘은 산책을 했다\n마음이 편해졌다');assert.equal(calls.filter(c=>c.method==='draft').at(-1).data.text,'오늘은 산책을 했다\n마음이 편해졌다');
click('[data-tab=records]');await wait(30);click('[data-id=test]');click('#refine-note');await wait(30);assert.equal(w.document.querySelector('#refined').value,'오늘 산책을 했다.');w.document.querySelector('#refined').value='오늘 산책을 했다. 마음이 편했다.';click('#accept-refinement');await wait(30);assert.equal(entry.text,'오늘은 산책을 했다');assert.equal(entry.refined,'오늘 산책을 했다. 마음이 편했다.');
failRefine=true;click('#refine-note');await wait(30);assert.ok(w.document.querySelector('.inline-error').textContent.includes('원문은 보관'));assert.equal(entry.text,'오늘은 산책을 했다');console.log('PASS: partial recovery / resume / draft persistence / diary date / editable refinement / original preservation / AI failure recovery');w.close();})().catch(e=>{console.error(e);w.close();process.exitCode=1});
