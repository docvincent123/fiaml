export type User={id:string;name:string;role:string;specialty:string;permissions:string[];sid:string;onShift?:boolean};
export let token=sessionStorage.getItem('quremed-token')||'';
export function setToken(value:string){token=value;if(value)sessionStorage.setItem('quremed-token',value);else sessionStorage.removeItem('quremed-token');}
let pending=0,writes=0,last='',connectionError='',writeError='',saved='';
window.addEventListener('acknowledge-save-error',()=>{writeError='';publish()});
const publish=()=>window.dispatchEvent(new CustomEvent('api-status',{detail:{pending,writes,last,saved,error:writeError||connectionError}}));
export async function api(path:string,method='GET',body?:unknown){
 const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),20000);pending++;if(method!=='GET')writes++;publish();
 try {let r:Response;try{r=await fetch('/api'+path,{method,headers:{'Content-Type':'application/json','X-RehaFlow-API':'1',...(token?{Authorization:`Bearer ${token}`}:{})},body:body===undefined?undefined:JSON.stringify(body),cache:'no-store',signal:controller.signal})}catch{connectionError='Немає відповіді сервера. Дія не підтверджена; перед повтором перевірте її стан.';if(method!=='GET')writeError=connectionError;throw new Error(connectionError)}
 const data=await r.json().catch(()=>{if(method!=='GET')writeError='Сервер повернув неочікувану відповідь. Перевірте стан дії перед повтором.';throw new Error('Неочікувана відповідь сервера')});connectionError='';last=new Date().toLocaleTimeString('uk-UA');
 if(!r.ok){if(r.status===401&&path!=='/auth/login')window.dispatchEvent(new Event('session-ended'));throw new Error(data.message||'Не вдалося виконати операцію')}
 if(method!=='GET'){saved=new Date().toLocaleTimeString('uk-UA');window.dispatchEvent(new Event('care-changed'));}return data;
 }finally{clearTimeout(timeout);pending--;if(method!=='GET')writes--;publish()}
}
export async function qrImage(uid:string){const r=await fetch(`/api/beds/qr/${uid}/image`,{headers:{Authorization:`Bearer ${token}`}});if(!r.ok){const d=await r.json();throw new Error(d.message);}return URL.createObjectURL(await r.blob());}
