#ifndef WEBPROXYHTML_H
#define WEBPROXYHTML_H

#include <string>

namespace tgnet {

static const char* WEBPROXY_HTML = R"HTML(<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Telegram Web Proxy</title>
<style>
body{font:16px system-ui,sans-serif;margin:0;min-height:100vh;display:grid;place-items:center;background:#f4f6f8;color:#17212b}
main{width:min(34rem,calc(100% - 4rem));padding:2rem;text-align:center}h1{font-size:1.5rem}
#state{color:#5288c1}
.traffic{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.75rem;margin:1.5rem 0;text-align:left}
.traffic div{padding:1rem;border:1px solid #dce3e9;border-radius:.75rem;background:#fff}
.traffic dt{font-size:.8rem;color:#6c7883}.traffic dd{margin:.35rem 0 0;font-size:1.1rem;font-weight:600}
.traffic small{display:block;margin-top:.25rem;color:#5288c1;font-size:.8rem;font-weight:400}
.note{font-size:.8rem;color:#6c7883}iframe{display:none}
</style>
<main>
    <h1>Telegram Web Proxy</h1><p id="state">Connecting to Telegram...</p>
    <dl class="traffic">
        <div><dt>Sent through HTTPS</dt><dd><span id="up-total">0 B</span><small id="up-rate">0 B/s</small></dd></div>
        <div><dt>Received through HTTPS</dt><dd><span id="down-total">0 B</span><small id="down-rate">0 B/s</small></dd></div>
    </dl>
    <p>Keep this page open while using Telegram.</p>
</main>
<script>
(()=>{
const relayOrigin = "%PROXY_HOST%"; // Will be replaced by C++
const token = location.hash.slice(1);
const state = document.getElementById('state');
const upTotal=document.getElementById('up-total'),downTotal=document.getElementById('down-total'),upRate=document.getElementById('up-rate'),downRate=document.getElementById('down-rate');
const traffic={up:0,down:0,lastUp:0,lastDown:0,lastAt:performance.now()};
const formatBytes=value=>{const units=['B','KiB','MiB','GiB','TiB'];let unit=0;while(value>=1024&&unit<units.length-1){value/=1024;unit++}return value.toFixed(unit&&value<100?1:0)+' '+units[unit]};
const refreshTraffic=()=>{const now=performance.now(),seconds=Math.max((now-traffic.lastAt)/1000,.001);upTotal.textContent=formatBytes(traffic.up);downTotal.textContent=formatBytes(traffic.down);upRate.textContent=formatBytes((traffic.up-traffic.lastUp)/seconds)+'/s';downRate.textContent=formatBytes((traffic.down-traffic.lastDown)/seconds)+'/s';traffic.lastUp=traffic.up;traffic.lastDown=traffic.down;traffic.lastAt=now};
setInterval(refreshTraffic,1000);

const browser = navigator.userAgent;
const channel=new MessageChannel(),port=channel.port1,pending=[],localQueueLimit=33554432;
let initialized=false,localClosed=false,iframe=null;
port.start();

const local=new WebSocket('ws://127.0.0.1:'+location.port+'/transport');
local.binaryType='arraybuffer';

let rtcGuard=null,rtcRetryTimer=0,rtcFailures=0;
const disposeRtc=guard=>{if(guard.first)guard.first.onicecandidate=null;if(guard.second){guard.second.onicecandidate=null;guard.second.ondatachannel=null}if(guard.receiver)guard.receiver.close();if(guard.channel)guard.channel.close();if(guard.first)guard.first.close();if(guard.second)guard.second.close()};
const stopRtc=()=>{if(rtcRetryTimer){clearTimeout(rtcRetryTimer);rtcRetryTimer=0}const guard=rtcGuard;rtcGuard=null;if(guard)disposeRtc(guard)};
const scheduleRtc=()=>{if(local.readyState!==WebSocket.OPEN||rtcGuard||rtcRetryTimer||rtcFailures>=5)return;const delay=Math.min(1000*(2**Math.max(0,rtcFailures-1)),30000);rtcRetryTimer=setTimeout(()=>{rtcRetryTimer=0;startRtc()},delay)};
const restartRtc=guard=>{if(rtcGuard!==guard)return;rtcGuard=null;disposeRtc(guard);rtcFailures++;scheduleRtc()};
const startRtc=async()=>{if(local.readyState!==WebSocket.OPEN||rtcGuard||rtcRetryTimer||rtcFailures>=5)return;if(typeof RTCPeerConnection!=='function'){rtcFailures=5;return}
 const guard={first:null,second:null,channel:null,receiver:null};rtcGuard=guard;
 try{
  guard.first=new RTCPeerConnection({iceServers:[]});guard.second=new RTCPeerConnection({iceServers:[]});guard.channel=guard.first.createDataChannel('tab-lifecycle');
  const toFirst=[],toSecond=[];
  const loopbackCandidate=candidate=>{const value=candidate.toJSON(),parts=value.candidate.split(/\s+/);if(parts.length<6)return null;parts[4]='127.0.0.1';value.candidate=parts.join(' ');return value};
  const relayCandidate=(peer,queue,candidate)=>{candidate=candidate&&loopbackCandidate(candidate);if(!candidate)return;if(peer.remoteDescription)peer.addIceCandidate(candidate).catch(()=>{});else queue.push(candidate)};
  guard.first.onicecandidate=event=>relayCandidate(guard.second,toSecond,event.candidate);
  guard.second.onicecandidate=event=>relayCandidate(guard.first,toFirst,event.candidate);
  guard.second.ondatachannel=event=>{guard.receiver=event.channel};
  const opened=new Promise((resolve,reject)=>{const check=()=>{if(guard.first.connectionState==='failed'||guard.first.connectionState==='closed'||guard.second.connectionState==='failed'||guard.second.connectionState==='closed')reject()};guard.channel.addEventListener('open',resolve,{once:true});guard.channel.addEventListener('close',reject,{once:true});guard.first.addEventListener('connectionstatechange',check);guard.second.addEventListener('connectionstatechange',check)});
  const offer=await guard.first.createOffer();await guard.first.setLocalDescription(offer);
  await guard.second.setRemoteDescription(offer);
  await Promise.all(toSecond.splice(0).map(candidate=>guard.second.addIceCandidate(candidate)));
  const answer=await guard.second.createAnswer();await guard.second.setLocalDescription(answer);
  await guard.first.setRemoteDescription(answer);
  await Promise.all(toFirst.splice(0).map(candidate=>guard.first.addIceCandidate(candidate)));
  let timeout=0;try{await Promise.race([opened,new Promise((resolve,reject)=>{timeout=setTimeout(reject,10000)})])}finally{clearTimeout(timeout)}
  if(rtcGuard!==guard||local.readyState!==WebSocket.OPEN){restartRtc(guard);return}
  rtcFailures=0;
  const check=()=>{if(guard.channel.readyState==='closed'||guard.first.connectionState==='failed'||guard.first.connectionState==='closed'||guard.second.connectionState==='failed'||guard.second.connectionState==='closed')restartRtc(guard)};
  guard.channel.addEventListener('close',check,{once:true});guard.first.addEventListener('connectionstatechange',check);guard.second.addEventListener('connectionstatechange',check);
 }catch(error){restartRtc(guard)}
};

local.onopen=()=>{local.send(JSON.stringify({t:'auth',token,browser}));startRtc()};
local.onclose=()=>{stopRtc();localClosed=true;state.textContent='Disconnected.';if(initialized)port.postMessage({t:'close'})};
local.onerror=()=>{state.textContent='Could not connect to Telegram.'};

const openBridge=url=>{if(iframe||localClosed)return;iframe=document.createElement('iframe');iframe.sandbox='allow-scripts allow-same-origin';iframe.referrerPolicy='no-referrer';
 iframe.onload=()=>{if(localClosed)return;if(initialized){state.textContent='Reloaded. Reopen browser.';local.close();return}iframe.contentWindow.postMessage({t:'tproxy-init',v:1},relayOrigin,[channel.port2]);initialized=true;while(pending.length){const data=pending.shift();port.postMessage(data,[data])}};
 iframe.src=url;document.body.appendChild(iframe)};

local.onmessage=e=>{if(e.data instanceof ArrayBuffer){if(initialized)port.postMessage(e.data,[e.data]);else pending.push(e.data);return}
 if(typeof e.data!=='string')return;let control=null;try{control=JSON.parse(e.data)}catch(error){return}
 if(!control||typeof control!=='object'||control.t!=='bridge'||typeof control.url!=='string'||!control.url.startsWith(relayOrigin+'/?bridge='))return;openBridge(control.url)};

port.onmessage=e=>{if(e.data instanceof ArrayBuffer){if(local.readyState===WebSocket.OPEN){if(local.bufferedAmount>localQueueLimit-e.data.byteLength){state.textContent='Telegram is not consuming proxy data.';local.close();return}try{local.send(e.data)}catch(error){local.close()}}return}
 if(e.data&&e.data.t==='status'){const s=e.data.state;state.textContent=s==='connected'?'Connected. Keep this tab open.':s==='failed'?'Proxy site unavailable.':'Connecting to proxy...';if(local.readyState===WebSocket.OPEN)local.send(JSON.stringify(e.data));return}
 if(e.data&&e.data.t==='traffic'){const up=e.data.up,down=e.data.down;if(Number.isSafeInteger(up)&&up>=0&&Number.isSafeInteger(down)&&down>=0){traffic.up+=up;traffic.down+=down}return}
 if(e.data&&e.data.t==='close'){state.textContent='Proxy closed connection.';local.close()}}

addEventListener('pagehide',stopRtc,{once:true});
addEventListener('pageshow',startRtc);
})();
</script>
)HTML";

} // namespace tgnet

#endif
