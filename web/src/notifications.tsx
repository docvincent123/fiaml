import {useEffect,useState} from 'react';
import {api,token,type User} from './api';
let context:AudioContext|undefined;
const android=()=> (window as any).QureMedAndroid;
const enabled=()=>localStorage.getItem('rehaflow-sound')!=='off';
function unlock(){if(!enabled())return;context??=new AudioContext();void context.resume().catch(()=>{});}
window.addEventListener('pointerdown',unlock,{once:true});
export function chime(){if(!enabled()||!context||context.state!=='running')return;const start=context.currentTime;for(const [offset,hz] of [[0,660],[.17,880]]){const osc=context.createOscillator(),gain=context.createGain();osc.frequency.value=hz;gain.gain.setValueAtTime(0,start+offset);gain.gain.linearRampToValueAtTime(.07,start+offset+.02);gain.gain.exponentialRampToValueAtTime(.001,start+offset+.2);osc.connect(gain);gain.connect(context.destination);osc.start(start+offset);osc.stop(start+offset+.22);}}
export function StaffNotifications({user,notify}:{user:User;notify:(s:string)=>void}){
 useEffect(()=>{if(android())return;let live=true,busy=false;const key='rehaflow-notices:'+user.id;let seen=new Set<string>();try{seen=new Set(JSON.parse(sessionStorage.getItem(key)||'[]'))}catch{}
 async function poll(){if(busy||document.hidden||!token)return;busy=true;try{const data=await api('/care/notification-feed');if(!live)return;const ids=new Set<string>(data.events.map((e:any)=>e.id));const fresh=data.events.filter((e:any)=>!seen.has(e.id));if(fresh.length){notify('Нові повідомлення / зміни у завданнях: '+fresh.length);chime();}seen=ids;sessionStorage.setItem(key,JSON.stringify([...seen]));}catch{}finally{busy=false}}
 void poll();const timer=setInterval(poll,15000);return()=>{live=false;clearInterval(timer)};
 },[user.id,notify]);return null;
}
export function NotificationSettings(){const[sound,setSound]=useState(enabled()),[state,setState]=useState<any>(null);
 useEffect(()=>{const update=()=>{try{const raw=android()?.notificationStatus?.();setState(raw?JSON.parse(raw):null)}catch{}};update();const t=setInterval(update,3000);return()=>clearInterval(t)},[]);
 return <section className="panel padded"><h2>Сповіщення та звук</h2>{android()&&!android().enableAlerts?<p>Оновіть APK, щоб увімкнути звукові сповіщення у фоні.</p>:android()?<><p>{state?.running?state.message:state?.message||'Увімкніть сповіщення для цієї зміни.'}</p><button className="primary" onClick={()=>android().enableAlerts()}>Увімкнути сповіщення</button><button className="ghost" onClick={()=>android().testAlert()}>Перевірити звук</button><button className="ghost" onClick={()=>android().alertSettings()}>Звук і дозволи Android</button><button className="ghost" onClick={()=>android().disableAlerts()}>Вимкнути перевірку у фоні</button><p className="muted">Потрібні Wi-Fi центру й дозвіл Android. Режим «Не турбувати» та енергозбереження можуть вимикати звук або затримувати доставку. Android 15 обмежує фонову синхронізацію; після системної зупинки відкрийте застосунок.</p></>:<><label className="check"><input type="checkbox" checked={sound} onChange={e=>{setSound(e.target.checked);localStorage.setItem('rehaflow-sound',e.target.checked?'on':'off');if(e.target.checked)unlock();}}/>Короткий звук нових завдань і повідомлень</label><button className="ghost" onClick={()=>{unlock();chime();}}>Перевірити звук</button></>}</section>;
}
