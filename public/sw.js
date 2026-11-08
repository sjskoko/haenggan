/* Private journal data is never cached. */
self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',event=>event.waitUntil(self.clients.claim()));
self.addEventListener('push',event=>{
 let data={};try{data=event.data?.json()||{};}catch{}
 event.waitUntil(self.registration.showNotification(data.title||'행간 · 오늘을 돌아볼 시간',{
 body:data.body||'오늘의 이야기를 남겨보세요.',icon:'/icon-192.png',badge:'/badge.png',
 tag:data.tag||'haenggan-daily',renotify:true,requireInteraction:true,vibrate:[180,100,180],
 actions:[{action:'write',title:'지금 회고하기'},{action:'dismiss',title:'닫기'}],data:{url:'/?write=1'}
 }));
});
self.addEventListener('notificationclick',event=>{event.notification.close();if(event.action==='dismiss')return;
 event.waitUntil((async()=>{const windows=await self.clients.matchAll({type:'window',includeUncontrolled:true});for(const client of windows){if(new URL(client.url).origin===self.location.origin){await client.navigate('/?write=1');return client.focus();}}return self.clients.openWindow('/?write=1');})());
});
