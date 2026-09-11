import {useState} from 'react';
import {useCareData,roleNames} from './care-ui';
import type {User} from './api';
import {NavLink} from 'react-router-dom';
export function Operations({user}:{user:User}){
 const {data,error}=useCareData('/operations');
 return <section className="panel padded"><h2>Сьогодні · {roleNames[user.role]}</h2>{error&&<p className="error">{error}</p>}{data&&<><div className="quick-grid">{data.metrics.map((m:any)=><NavLink className="quick" key={m.label} to={m.path}><div><h3>{m.value}</h3><p>{m.label}</p></div></NavLink>)}</div>{data.staff&&<><h3>На зміні</h3>{data.staff.length?data.staff.map((s:any)=><p key={s.id}>{s.name} · {roleNames[s.role]}</p>):<p>Активних змін немає</p>}</>}{data.maintenance&&<><h3>Стан сервера</h3><p>База даних відповідає · API {data.version}</p><p>Остання копія: {data.maintenance.backup?.completedAt?new Date(data.maintenance.backup.completedAt).toLocaleString('uk-UA'):'Ще не підтверджена'}</p>{(!data.maintenance.backup?.completedAt||Date.now()-new Date(data.maintenance.backup.completedAt).getTime()>36*3600000)&&<p className="error">Немає підтвердженої копії за останні 36 годин. Перевірте завдання резервування.</p>}<p>Перевірка відновлення: {data.maintenance.restore?.verifiedAt?new Date(data.maintenance.restore.verifiedAt).toLocaleString('uk-UA'):'Ще не виконана'}</p><p className="muted">Резервна копія на цьому ж диску не захищає від його поломки. Зберігайте додаткову копію на окремому носії.</p></>}</>}</section>
}
export function PatientTimeline({patient:p}:{patient:any}){
 const [limit,setLimit]=useState(30);
 const events:any[]=[];
 for(const a of p.admissions||[]){events.push({id:'ad'+a.id,at:a.admitted_at,title:'Надходження',body:a.doctor_name});if(a.discharged_at)events.push({id:'out'+a.id,at:a.discharged_at,title:'Виписка'});}
 for(const e of p.entries||[])events.push({id:'entry'+e.id,at:e.created_at,title:({ASSESSMENT:'Огляд',OBSERVATION:'Спостереження',REHAB:'Реабілітація',DISCHARGE:'Виписний підсумок',CORRECTION:'Виправлення'} as any)[e.kind],body:e.body,author:e.author});
 for(const e of p.notes||[])events.push({id:'note'+e.id,at:e.created_at,title:'Медичний запис',body:e.body,author:e.author});
 for(const h of p.handovers||[]){events.push({id:'h'+h.id,at:h.created_at,title:'Передача лікаря',body:h.from_name+' → '+h.to_name});if(h.accepted_at)events.push({id:'ha'+h.id,at:h.accepted_at,title:'Передачу прийнято',body:h.to_name});}
 for(const t of p.timelineTasks||[]){events.push({id:'t'+t.id,at:t.created_at,title:'Призначення',body:t.task_type,author:t.creator});if(t.taken_at)events.push({id:'take'+t.id,at:t.taken_at,title:'Взято в роботу',body:t.task_type,author:t.executor});if(t.completed_at)events.push({id:'done'+t.id,at:t.completed_at,title:t.not_done?'Не виконано':'Виконано',body:t.task_type+' · '+(t.outcome||''),author:t.executor});}
 events.sort((a,b)=>new Date(b.at).getTime()-new Date(a.at).getTime());
 return <section><h3>Стрічка подій</h3>{events.slice(0,limit).map(e=><article className="history" key={e.id}><strong>{e.title}</strong><small>{new Date(e.at).toLocaleString('uk-UA')} {e.author&&' · '+e.author}</small>{e.body&&<p>{e.body}</p>}</article>)}{events.length>limit&&<button className="ghost" onClick={()=>setLimit(n=>n+30)}>Показати ще</button>}</section>
}
