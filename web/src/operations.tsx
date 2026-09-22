import {useState} from 'react';
import {useCareData,roleNames} from './care-ui';
import type {User} from './api';
import {NavLink} from 'react-router-dom';
export function Operations({user}:{user:User}){
 const {data,error}=useCareData('/operations');
 return <div className="ops-layout">
 <section className="panel padded">
 <div className="ops-heading"><h2>Сьогодні · {roleNames[user.role]}</h2></div>
 {error&&<p className="error">{error}</p>}
 {!data&&!error&&<p className="muted" role="status">Завантажуємо робочі показники…</p>}
 {data&&<div className="ops-metrics">{data.metrics.map((m:any)=><NavLink className="ops-metric" key={m.label} to={m.path}><strong>{m.value}</strong><span>{m.label}</span></NavLink>)}</div>}
 </section>
 {data&&<section className="panel padded">
 <div className="ops-heading"><h2>{data.staff?'Команда на зміні':'Найближчі процедури'}</h2></div>
 {data.staff?(data.staff.length?data.staff.map((s:any)=><div className="ops-staff" key={s.id}><span className="avatar" aria-hidden="true">{s.name.slice(0,2)}</span><div><strong>{s.name}</strong><small className="muted">{roleNames[s.role]||s.role}</small>{["NURSE","THERAPIST"].includes(s.role)&&<small className={s.alerts_seen_at&&Date.now()-Date.parse(s.alerts_seen_at)<90000?"muted":"overdue"}>{s.alerts_seen_at?"Остання перевірка сповіщень: "+new Date(s.alerts_seen_at).toLocaleTimeString("uk-UA"):"Перевірка сповіщень ще не підтверджена"}</small>}</div></div>):<p className="muted">Активних змін поки немає.</p>):(data.appointments?.length?data.appointments.map((a:any)=><NavLink className="ops-staff" key={a.id} to={'/patients?patient='+encodeURIComponent(a.patient_id)}><span className="pill">{new Date(a.starts_at).toLocaleTimeString('uk-UA',{hour:'2-digit',minute:'2-digit'})}</span><div><strong>{a.patient_name}</strong><small className="muted">{new Date(a.starts_at).toLocaleDateString('uk-UA')} · {a.cabinet_name}</small></div></NavLink>):<p className="muted">Найближчих записів немає.</p>)}
 </section>}
 {data?.maintenance&&<section className="panel padded ops-full"><div className="ops-heading"><h2>Стан сервера</h2><span className="pill">API {data.version}</span></div>
 <p>База даних відповідає</p>
 <div className="ops-status"><p>Остання копія: {data.maintenance.backup?.completedAt?new Date(data.maintenance.backup.completedAt).toLocaleString('uk-UA'):'Ще не підтверджена'}</p>
 {(!data.maintenance.backup?.completedAt||Date.now()-new Date(data.maintenance.backup.completedAt).getTime()>36*3600000)&&<p className="error">Немає підтвердженої копії за останні 36 годин. Перевірте завдання резервування.</p>}</div>
 <div className="ops-status"><p>Перевірка відновлення: {data.maintenance.restore?.verifiedAt?new Date(data.maintenance.restore.verifiedAt).toLocaleString('uk-UA'):'Ще не виконана'}</p></div>
 <p className="muted">Зберігайте додаткову резервну копію на окремому носії: копія на тому самому диску не захищає від його поломки.</p>
 </section>}
 </div>
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
