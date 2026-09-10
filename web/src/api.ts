export type User={id:string;name:string;role:string;specialty:string;permissions:string[];sid:string;onShift?:boolean};
export let token=sessionStorage.getItem('quremed-token')||'';
export function setToken(value:string){token=value;if(value)sessionStorage.setItem('quremed-token',value);else sessionStorage.removeItem('quremed-token');}
let pending=0,last='',connectionError='';
const publish=()=>window.dispatchEvent(new CustomEvent('api-status',{detail:{pending,last,error:connectionError}}));
export async function api(path:string,method='GET',body?:unknown){
 const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),20000);pending++;publish();
 try {let r:Response;try{r=await fetch('/api'+path,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:body===undefined?undefined:JSON.stringify(body),cache:'no-store',signal:controller.signal})}catch{connectionError='Немає відповіді сервера. Дія не підтверджена; перед повтором перевірте її стан.';throw new Error(connectionError)}
 const data=await r.json();connectionError='';last=new Date().toLocaleTimeString('uk-UA');
 if(!r.ok){if(r.status===401&&path!=='/auth/login')window.dispatchEvent(new Event('session-ended'));throw new Error(data.message||'Не вдалося виконати операцію')}
 if(method!=='GET')window.dispatchEvent(new Event('care-changed'));return data;
 }finally{clearTimeout(timeout);pending--;publish()}
}
export async function qrImage(uid:string){const r=await fetch(`/api/beds/qr/${uid}/image`,{headers:{Authorization:`Bearer ${token}`}});if(!r.ok){const d=await r.json();throw new Error(d.message);}return URL.createObjectURL(await r.blob());}
