import 'reflect-metadata';
import {test,before,after} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {PGlite} from '@electric-sql/pglite';
import {Care} from '../src/care';
import {Database} from '../src/db';
import {hashPassword} from '../src/security';
import {permissions,type Actor} from '../src/access';
let c:Care,db:Database,close:()=>Promise<void>,admin:Actor,d1:Actor,d2:Actor,d3:Actor,n1:Actor,n2:Actor,therapist:Actor;
async function actor(role:string,name:string):Promise<Actor>{const u=(await db.query('INSERT INTO users(name,login,password_hash,role,specialty) VALUES($1,$2,$3,$4,$5) RETURNING *',[name,'care-'+name,'not-a-login-hash',role,'Тест'])).rows[0];return {id:u.id,name,role,specialty:'Тест',permissions:permissions(u),sid:crypto.randomUUID()};}
before(async()=>{
 if(process.env.TEST_DATABASE_URL){const control=new Database(process.env.TEST_DATABASE_URL);const schema='care_'+crypto.randomUUID().replaceAll('-','');await control.query('CREATE SCHEMA '+schema);const url=new URL(process.env.TEST_DATABASE_URL);url.searchParams.set('options','-c search_path='+schema);db=new Database(url.toString());await db.migrate();close=async()=>{await db.pool.end();await control.query('DROP SCHEMA '+schema+' CASCADE');await control.pool.end()};}
 else{const pg=new PGlite();for(const file of ['001_initial.sql','002_care.sql'])await pg.exec(readFileSync(new URL('../sql/'+file,import.meta.url),'utf8'));let queue=Promise.resolve();db={query:(q:string,v:any[]=[])=>pg.query(q,v),tx:async(fn:any)=>{let release!:()=>void;const before=queue;queue=new Promise<void>(r=>release=r);await before;try{return await pg.transaction(tx=>fn({query:(q:string,v:any[]=[])=>tx.query(q,v)}))}finally{release()}}} as any;close=()=>pg.close();}
 c=new Care(db,'test-secret-with-at-least-thirty-two-characters');
 admin=await actor('ADMIN','careadmin');d1=await actor('DOCTOR','first');d2=await actor('DOCTOR','second');d3=await actor('DOCTOR','third');n1=await actor('NURSE','nursefirst');n2=await actor('NURSE','nursesecond');therapist=await actor('THERAPIST','therapist');
});
after(async()=>close?.());
const patient=()=>c.register(admin,{name:'Пацієнт '+crypto.randomUUID(),doctor_id:d1.id,birth_date:'1990-01-01',address:'Адреса',emergency_contact:'Контакт'});
const task=(patient_id:string,more:any={})=>c.task(d1,{patient_id,description:'Тестове завдання',task_type:'Догляд',scheduled_at:new Date().toISOString(),...more});
test('All doctors see every active patient; current physician remains until intended recipient accepts',async()=>{
 const p=await patient();assert.ok((await c.patients(d2,{})).some(x=>x.id===p.id));assert.equal((await c.patients(d2,{mine:'true'})).some(x=>x.id===p.id),false);
 await c.handover(d1,{patient_ids:[p.id],to_id:d2.id,summary:'Підсумок 15 днів'});const h=(await c.handovers(d2)).find(x=>x.patient_id===p.id);assert.equal((await c.patient(d2,p.id)).admissions[0].doctor_id,d1.id);
 await assert.rejects(()=>c.acceptHandover(d3,h.id,'accept'));
 const results=await Promise.allSettled([c.acceptHandover(d2,h.id,'accept'),c.acceptHandover(d2,h.id,'accept')]);assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
 const result=await c.patient(d2,p.id);assert.equal(result.admissions[0].doctor_id,d2.id);assert.equal(result.handovers[0].from_id,d1.id);assert.equal(result.handovers[0].status,'ACCEPTED');
 await assert.rejects(()=>c.handover(d1,{patient_ids:[p.id],to_id:d3.id,summary:'Не свій пацієнт'}));
});
test('15-day due date, atomic multi-patient handover and no registration bypass',async()=>{
 const a=await patient(),b=await patient();await db.query("UPDATE admissions SET doctor_since=now()-interval '16 days' WHERE patient_id=$1",[a.id]);const row=(await c.patients(d1,{mine:'true'})).find(x=>x.id===a.id);assert.ok(new Date(row.handover_due)<new Date());
 await c.handover(d1,{patient_ids:[b.id],to_id:d2.id,summary:'Перша'});await assert.rejects(()=>c.handover(d1,{patient_ids:[a.id,b.id],to_id:d3.id,summary:'Конфлікт'}));assert.equal((await c.handovers(d1)).filter(x=>x.patient_id===a.id).length,0);
 await assert.rejects(()=>c.editPatient(admin,a.id,{...a,doctor_id:d3.id}));
});
test('Readmission and discharge preserve entries and cancel stale handovers',async()=>{const p=await patient();const e=await c.entry(d1,p.id,{kind:'ASSESSMENT',body:'Огляд',data:{diagnosis:'Опис',allergies:'Уточнено',plan:'План'}});await c.handover(d1,{patient_ids:[p.id],to_id:d2.id,summary:'Передача'});const h=(await c.handovers(d2)).find(x=>x.patient_id===p.id);await c.discharge(admin,p.id);await assert.rejects(()=>c.acceptHandover(d2,h.id,'accept'));await c.readmit(admin,p.id,{doctor_id:d2.id,care_type:'OUTPATIENT',referral:'Повторно'});const current=await c.patient(d2,p.id);assert.equal(current.admissions.length,2);assert.equal(current.entries[0].id,e.id);assert.notEqual(current.entries[0].admission_id,current.admissions[0].id);});
test('Duplicate guard rolls back; registration revisions preserve earlier values',async()=>{const p=await patient();await assert.rejects(()=>c.register(admin,{name:p.name,birth_date:p.birth_date instanceof Date?'1990-01-01':String(p.birth_date).slice(0,10)}));await c.editPatient(admin,p.id,{...p,birth_date:'1990-01-01',doctor_id:d1.id,address:'Нова адреса'});const revision=(await db.query('SELECT previous FROM patient_revisions WHERE patient_id=$1',[p.id])).rows[0];assert.equal(revision.previous.address,'Адреса');});
test('Worker role separation, repeat courses, identity confirmation and not-done reason',async()=>{const p=await patient();await c.shift(n1,{start:true});await c.shift(n2,{start:true});await c.shift(therapist,{start:true});const course=await task(p.id,{executor_role:'THERAPIST',repeat_count:15,interval_hours:24});assert.equal(course.count,15);assert.equal((await db.query('SELECT count(*)::int n FROM tasks WHERE course_id=$1',[course.course_id])).rows[0].n,15);await assert.rejects(()=>c.taskAction(n1,course.id,'claim'));await c.taskAction(therapist,course.id,'claim');await assert.rejects(()=>c.taskAction(therapist,course.id,'complete'));await assert.rejects(()=>c.taskAction(therapist,course.id,'not-done',{identity_confirmed:true}));const result=await c.taskAction(therapist,course.id,'not-done',{identity_confirmed:true,outcome:'Пацієнт відмовився'});assert.equal(result.not_done,true);assert.ok(result.completed_at);await assert.rejects(()=>task(p.id,{medication:'Тестовий препарат'}));});
test('Two competing claims yield one owner; team handover transfers remaining work once',async()=>{const p=await patient(),t=await task(p.id);const r=await Promise.allSettled([c.taskAction(n1,t.id,'claim'),c.taskAction(n2,t.id,'claim')]);assert.equal(r.filter(x=>x.status==='fulfilled').length,1);const winner=r[0].status==='fulfilled'?n1:n2,receiver=winner===n1?n2:n1;const h=await c.teamHandover(winner,{recipient_id:receiver.id,summary:'Контроль стану'});await assert.rejects(()=>c.acceptTeam(therapist,h.id));await c.acceptTeam(receiver,h.id);assert.equal((await db.query('SELECT taken_by FROM tasks WHERE id=$1',[t.id])).rows[0].taken_by,receiver.id);await assert.rejects(()=>c.acceptTeam(receiver,h.id));await c.taskAction(receiver,t.id,'complete',{identity_confirmed:true});});
test('Observations and rehab entries enforce roles, shape and immutable corrections',async()=>{const p=await patient();await c.shift(n1,{start:true});await assert.rejects(()=>c.entry(n1,p.id,{kind:'ASSESSMENT',body:'Не дозволено',data:{}}));await assert.rejects(()=>c.entry(n1,p.id,{kind:'OBSERVATION',body:'Опис',data:{spo2:101,observed_at:new Date().toISOString()}}));await c.entry(n1,p.id,{kind:'OBSERVATION',body:'Опис',data:{spo2:98,observed_at:new Date().toISOString()}});await c.entry(therapist,p.id,{kind:'REHAB',body:'Заняття',data:{goals:'Мета',assessment:'Шкала',result:'Результат',next_plan:'Наступне'}});const e=await c.entry(d1,p.id,{kind:'ASSESSMENT',body:'Огляд',data:{diagnosis:'Опис',allergies:'Невідомо',plan:'План'}});await assert.rejects(()=>c.entry(d2,p.id,{kind:'CORRECTION',body:'Чужий запис',corrects_id:e.id}));await c.entry(d1,p.id,{kind:'CORRECTION',body:'Уточнення до запису',corrects_id:e.id});assert.equal((await db.query('SELECT body FROM clinical_entries WHERE id=$1',[e.id])).rows[0].body,'Огляд');});
test('Messages can only be acknowledged by recipient; documents validate type and are audited',async()=>{const p=await patient();const m=await c.message(admin,{patient_id:p.id,recipient_id:d1.id,body:'Спостереження',urgent:true});await assert.rejects(()=>c.readMessage(d2,m.id));await c.readMessage(d1,m.id);assert.ok((await c.messages(d1)).find(x=>x.id===m.id).read_at);await assert.rejects(()=>c.upload(admin,p.id,{filename:'x.pdf',mime:'application/pdf',base64:Buffer.from('<script>').toString('base64')}));const d=await c.upload(admin,p.id,{filename:'test.pdf',mime:'application/pdf',base64:Buffer.from('%PDF-1.4 test fixture').toString('base64')});assert.equal((await c.document(d1,d.id)).filename,'test.pdf');});
test('Staff cannot be double-booked across different cabinets; floors are returned',async()=>{const a=await patient(),b=await patient();const room=await c.room(admin,{room_number:'care-floor',floor:'3'});assert.equal((await c.rooms(admin)).find(r=>r.id===room.id).floor,'3');const first=await c.cabinet(admin,{name:'care-one',type:'gym'}),second=await c.cabinet(admin,{name:'care-two',type:'gym'});const time={starts_at:'2030-02-01T10:00:00Z',ends_at:'2030-02-01T11:00:00Z',staff_id:therapist.id};const results=await Promise.allSettled([c.appointment(admin,{...time,patient_id:a.id,cabinet_id:first.id}),c.appointment(admin,{...time,patient_id:b.id,cabinet_id:second.id})]);assert.equal(results.filter(r=>r.status==='fulfilled').length,1);assert.equal((await c.appointments(therapist,{mine:'true'})).length,1);});

test('Registrar can place patients and book visits but cannot change infrastructure or send messages',async()=>{
 const r=await actor('REGISTRAR','reception-policy');
 const room=await c.room(admin,{room_number:'policy-room',floor:'1'});
 const bed=await c.bed(admin,room.id,{bed_number:'1'});
 const cabinet=await c.cabinet(admin,{name:'policy-cabinet',type:'gym'});
 assert.ok((await c.rooms(r)).some(x=>x.id===room.id));
 const p=await c.register(r,{name:'Registration policy',bed_id:bed.id});
 assert.ok((await c.cabinets(r)).some(x=>x.id===cabinet.id));
 const visit=await c.appointment(r,{patient_id:p.id,cabinet_id:cabinet.id,starts_at:'2032-01-01T10:00:00Z',ends_at:'2032-01-01T11:00:00Z'});
 assert.ok((await c.appointments(r)).some(x=>x.id===visit.id));
 await c.appointmentStatus(r,visit.id,{status:'CANCELLED'});
 await assert.rejects(()=>c.room(r,{room_number:'forbidden'}));
 await assert.rejects(()=>c.bed(r,room.id,{bed_number:'forbidden'}));
 await assert.rejects(()=>c.cabinet(r,{name:'forbidden',type:'gym'}));
 await assert.rejects(()=>c.task(r,{patient_id:p.id}));
 for(const sender of [r,d1,n1,therapist])await assert.rejects(()=>c.message(sender,{recipient_id:admin.id,body:'forbidden'}));
 const m=await c.message(admin,{recipient_id:r.id,body:'Reception notice'});
 assert.ok((await c.messages(r)).some(x=>x.id===m.id));await c.readMessage(r,m.id);
});
test('Finish work refuses owned tasks and preserves shift until work is returned',async()=>{
 const worker=await actor('NURSE','close-policy');await c.shift(worker,{start:true});
 const p=await patient();const t=await task(p.id);await c.taskAction(worker,t.id,'claim',{});
 await assert.rejects(()=>c.finishWork(worker));assert.equal(await c.onShift(worker),true);
 await c.taskAction(worker,t.id,'release',{});await c.finishWork(worker);assert.equal(await c.onShift(worker),false);
});

test('Successful login starts one shift for every staff role, never for admin',async()=>{
 const password='Automatic-Shift-Test-Password';const hash=await hashPassword(password);
 for(const role of ['REGISTRAR','DOCTOR','NURSE','THERAPIST','ADMIN']){
  const a=await actor(role,'autoshift-'+role.toLowerCase());await db.query('UPDATE users SET password_hash=$1 WHERE id=$2',[hash,a.id]);
  const credentials={login:'care-autoshift-'+role.toLowerCase(),password,device:'test-phone'};
  await c.login(credentials,'127.0.0.1');await c.login(credentials,'127.0.0.1');
  const count=Number((await db.query('SELECT count(*) FROM shifts WHERE user_id=$1 AND ends_at>now()',[a.id])).rows[0].count);
  assert.equal(count,role==='ADMIN'?0:1);if(role!=='ADMIN'){await c.shift(a,{start:false});assert.equal(await c.onShift(a),false);}
 }
 const bad=await actor('NURSE','no-login-shift');await assert.rejects(()=>c.login({login:'care-no-login-shift',password:'wrong'},'127.0.0.1'));assert.equal(await c.onShift(bad),false);
});
test('Notification feed contains only eligible events and no clinical content',async()=>{
 const nurse=await actor('NURSE','notification-nurse'),other=await actor('NURSE','notification-other'),rehab=await actor('THERAPIST','notification-rehab');
 await c.shift(nurse,{start:true});await c.shift(other,{start:true});await c.shift(rehab,{start:true});await c.shift(d1,{start:true});await c.shift(d2,{start:true});
 const p=await patient(),t=await task(p.id),rt=await task(p.id,{executor_role:'THERAPIST'});
 const message=await c.message(admin,{recipient_id:nurse.id,body:'Private message text',patient_id:p.id});
 const feed=await c.notificationFeed(nurse);assert.ok(feed.events.some(e=>e.id.startsWith('task:'+t.id)));assert.ok(feed.events.some(e=>e.id==='message:'+message.id));
 assert.ok(!feed.events.some(e=>e.id.startsWith('task:'+rt.id)));assert.ok(!JSON.stringify(feed).includes(p.name));assert.ok(!JSON.stringify(feed).includes('Private message text'));
 assert.ok(!(await c.notificationFeed(other)).events.some(e=>e.id==='message:'+message.id));
 await c.taskAction(other,t.id,'claim',{});assert.ok(!(await c.notificationFeed(nurse)).events.some(e=>e.id.startsWith('task:'+t.id)));
 await c.taskAction(other,t.id,'complete',{identity_confirmed:true,outcome:'Completed'});
 assert.ok((await c.notificationFeed(d1)).events.some(e=>e.id==='result:'+t.id));assert.ok(!(await c.notificationFeed(d2)).events.some(e=>e.id==='result:'+t.id));
 await c.readMessage(nurse,message.id);assert.ok(!(await c.notificationFeed(nurse)).events.some(e=>e.id==='message:'+message.id));
 await c.shift(nurse,{start:false});assert.deepEqual(await c.notificationFeed(nurse),{events:[],active:false});
});

test('Operations respect roles, acknowledgements are idempotent and timeline excludes registrar clinical tasks',async()=>{
 const p=await patient(),t=await task(p.id,{scheduled_at:new Date(Date.now()-3600000).toISOString()});
 await c.shift(n1,{start:true});await c.shift(therapist,{start:true});
 await assert.rejects(()=>c.taskAction(therapist,t.id,'acknowledge'));
 await c.taskAction(n1,t.id,'acknowledge');await c.taskAction(n1,t.id,'acknowledge');
 const listed=(await c.tasks(d1)).find((x:any)=>x.id===t.id);assert.equal(Number(listed.acknowledged_count),1);
 const own=(await c.tasks(n1)).find((x:any)=>x.id===t.id);assert.equal(own.acknowledged_by_me,true);
 const registrar=await actor('REGISTRAR','operationsregistrar');
 const reg=await c.operations(registrar);assert.equal('maintenance' in reg,false);assert.equal('staff' in reg,false);
 assert.equal(reg.metrics.some(x=>x.label==='Прострочених завдань'),false);
 const doc=await c.operations(d1);assert.ok(doc.metrics.find(x=>x.label==='Прострочених завдань')!.value>=1);
 assert.ok((await c.patient(d1,p.id)).timelineTasks.some((x:any)=>x.id===t.id));
 assert.deepEqual((await c.patient(registrar,p.id)).timelineTasks,[]);
 assert.ok('maintenance' in await c.operations(admin));
});
