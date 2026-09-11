import {useState} from 'react';
import {createPortal} from 'react-dom';
import {BrandMark} from './brand';
import './discharge.css';
const date=(v:any)=>v?new Date(v).toLocaleDateString('uk-UA'):'—';
const time=(v:any)=>v?new Date(v).toLocaleString('uk-UA'):'—';
export function DischargeReport({patient:p}:{patient:any}){
 const [admissionId,setAdmissionId]=useState(p.admissions?.[0]?.id||'');
 const ad=p.admissions?.find((a:any)=>a.id===admissionId);
 if(!ad)return <p>Госпіталізацію не знайдено.</p>;
 const entries=(p.entries||[]).filter((e:any)=>e.admission_id===ad.id);
 const summary=entries.find((e:any)=>e.kind==='DISCHARGE');
 const corrections=summary?entries.filter((e:any)=>e.corrects_id===summary.id):[];
 const tasks=(p.timelineTasks||[]).filter((t:any)=>t.admission_id===ad.id);
 const completed=tasks.filter((t:any)=>t.status==='COMPLETED'&&!t.not_done);
 const other=tasks.filter((t:any)=>t.status!=='COMPLETED'||t.not_done);
 const document=<article className="discharge-document">
 <header><BrandMark/><div><strong>RehaFlow · QureMed Industries</strong><h1>{ad.discharged_at?'Виписка з історії лікування':'Проміжний підсумок лікування'}</h1></div></header>
 {!summary&&<p className="report-warning">Виписний підсумок лікаря ще не заповнений. Документ неповний.</p>}
 <p><b>Пацієнт:</b> {p.name} · <b>Дата народження:</b> {date(p.birth_date)}</p>
 <p><b>Період лікування:</b> {date(ad.admitted_at)} — {ad.discharged_at?date(ad.discharged_at):'лікування триває'}</p>
 <p><b>Лікуючий лікар:</b> {ad.doctor_name||'Не призначено'}</p>
 <section><h2>Діагноз при виписці</h2><p>{summary?.data?.diagnosis||'Не внесено у виписний підсумок'}</p></section>
 <section><h2>Перебіг і результат лікування</h2><p>{summary?.data?.treatment_summary||summary?.body||'Підсумок не внесено'}</p></section>
 <section><h2>Фактично виконані призначення ({completed.length})</h2>{completed.length?<table><thead><tr><th>Дата й час</th><th>Лікування / процедура</th><th>Виконавець і результат</th></tr></thead><tbody>{completed.map((t:any)=><tr key={t.id}><td>{time(t.completed_at)}</td><td>{t.task_type}<p>{t.description}</p>{t.medication&&<p>{t.medication} · {t.dose} {t.dose_unit} · {t.route}</p>}</td><td>{t.executor||'—'}<p>{t.outcome||'Результат окремо не описано'}</p></td></tr>)}</tbody></table>:<p>Підтверджених виконань у цій госпіталізації немає.</p>}</section>
 {other.length>0&&<section><h2>Інші призначення — не враховані як виконане лікування</h2>{other.map((t:any)=><p key={t.id}>{t.task_type} · {t.not_done?'Не виконано':t.status==='CANCELLED'?'Скасовано':t.status==='IN_PROGRESS'?'У роботі':'Заплановано'}{t.not_done&&t.outcome?' · '+t.outcome:''}</p>)}</section>}
 {entries.some((e:any)=>e.kind==='REHAB')&&<section><h2>Реабілітація</h2>{entries.filter((e:any)=>e.kind==='REHAB').map((e:any)=><div key={e.id}><p>{time(e.created_at)} · {e.author}</p><p>{e.body}</p><p>{e.data?.result}</p>{entries.filter((c:any)=>c.corrects_id===e.id).map((c:any)=><p key={c.id}><b>Виправлення:</b> {c.body} · {c.author} · {time(c.created_at)}</p>)}</div>)}</section>}
 <section><h2>Стан при виписці</h2><p>{summary?.data?.discharge_condition||'Не внесено'}</p></section>
 <section><h2>Рекомендації</h2><p>{summary?.data?.recommendations||'Не внесено'}</p><h2>Подальше спостереження</h2><p>{summary?.data?.follow_up||'Не внесено'}</p></section>
 {corrections.map((e:any)=><section key={e.id}><h2>Виправлення виписного підсумку</h2><p>{e.body}</p><p>{e.author} · {time(e.created_at)}</p></section>)}
 <footer><p>Підсумок склав: {summary?.author||'—'} · {time(summary?.created_at)}</p><p>Підпис лікаря: ____________________</p><small>Госпіталізація: {ad.id}</small></footer>
 </article>;
 return <><label className="field">Період лікування<select value={admissionId} onChange={e=>setAdmissionId(e.target.value)}>{p.admissions.map((a:any)=><option key={a.id} value={a.id}>{date(a.admitted_at)} — {a.discharged_at?date(a.discharged_at):'триває'}</option>)}</select></label><p className="muted">Виписний підсумок лікар заповнює у «Медичні записи → Виписний підсумок». До проведеного лікування входять лише підтверджені виконання.</p><button className="primary no-print" onClick={()=>{const native=(window as any).QureMedAndroid;if(native)native.print();else window.print();}}>Друк / зберегти PDF виписки</button>{document}{createPortal(<div className="discharge-print">{document}</div>,window.document.body)}</>;
}

