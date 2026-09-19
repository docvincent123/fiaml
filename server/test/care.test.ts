import {AuthGuard} from '../src/http';
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
 else{const pg=new PGlite();for(const file of ['001_initial.sql','002_care.sql','003_room_retirement.sql','004_staff_workflow.sql','005_patient_numbers.sql','006_request_receipts.sql'])await pg.exec(readFileSync(new URL('../sql/'+file,import.meta.url),'utf8'));let queue=Promise.resolve();db={query:(q:string,v:any[]=[])=>pg.query(q,v),tx:async(fn:any)=>{let release!:()=>void;const before=queue;queue=new Promise<void>(r=>release=r);await before;try{return await pg.transaction(tx=>fn({query:(q:string,v:any[]=[])=>tx.query(q,v)}))}finally{release()}}} as any;close=()=>pg.close();}
 c=new Care(db,'test-secret-with-at-least-thirty-two-characters');
 admin=await actor('ADMIN','careadmin');d1=await actor('DOCTOR','first');d2=await actor('DOCTOR','second');d3=await actor('DOCTOR','third');n1=await actor('NURSE','nursefirst');n2=await actor('NURSE','nursesecond');therapist=await actor('THERAPIST','therapist');
});
after(async()=>close?.());
const patient=()=>registerTest(admin,{name:'Пацієнт '+crypto.randomUUID(),doctor_id:d1.id,birth_date:'1990-01-01',address:'Адреса',emergency_contact:'Контакт'});
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
test('Duplicate guard rolls back; registration revisions preserve earlier values',async()=>{const p=await patient();await assert.rejects(()=>registerTest(admin,{name:p.name,birth_date:p.birth_date instanceof Date?'1990-01-01':String(p.birth_date).slice(0,10)}));await c.editPatient(admin,p.id,{...p,birth_date:'1990-01-01',doctor_id:d1.id,address:'Нова адреса'});const revision=(await db.query('SELECT previous FROM patient_revisions WHERE patient_id=$1',[p.id])).rows[0];assert.equal(revision.previous.address,'Адреса');});
test('Worker role separation, repeat courses, identity confirmation and not-done reason',async()=>{const p=await patient();await approvedShift(n1);await approvedShift(n2);await approvedShift(therapist);const course=await task(p.id,{executor_role:'THERAPIST',repeat_count:15,interval_hours:24});assert.equal(course.count,15);assert.equal((await db.query('SELECT count(*)::int n FROM tasks WHERE course_id=$1',[course.course_id])).rows[0].n,15);await assert.rejects(()=>c.taskAction(n1,course.id,'claim'));await c.taskAction(therapist,course.id,'claim');await assert.rejects(()=>c.taskAction(therapist,course.id,'complete'));await assert.rejects(()=>c.taskAction(therapist,course.id,'not-done',{identity_confirmed:true}));const result=await c.taskAction(therapist,course.id,'not-done',{identity_confirmed:true,outcome:'Пацієнт відмовився'});assert.equal(result.not_done,true);assert.ok(result.completed_at);await assert.rejects(()=>task(p.id,{medication:'Тестовий препарат'}));});
test('Two competing claims yield one owner; team handover transfers remaining work once',async()=>{const p=await patient(),t=await task(p.id);const r=await Promise.allSettled([c.taskAction(n1,t.id,'claim'),c.taskAction(n2,t.id,'claim')]);assert.equal(r.filter(x=>x.status==='fulfilled').length,1);const winner=r[0].status==='fulfilled'?n1:n2,receiver=winner===n1?n2:n1;const h=await c.teamHandover(winner,{recipient_id:receiver.id,summary:'Контроль стану'});await assert.rejects(()=>c.acceptTeam(therapist,h.id));await c.acceptTeam(receiver,h.id);assert.equal((await db.query('SELECT taken_by FROM tasks WHERE id=$1',[t.id])).rows[0].taken_by,receiver.id);await assert.rejects(()=>c.acceptTeam(receiver,h.id));await c.taskAction(receiver,t.id,'complete',{identity_confirmed:true});});
test('Observations and rehab entries enforce roles, shape and immutable corrections',async()=>{const p=await patient();await approvedShift(n1);await assert.rejects(()=>c.entry(n1,p.id,{kind:'ASSESSMENT',body:'Не дозволено',data:{}}));await assert.rejects(()=>c.entry(n1,p.id,{kind:'OBSERVATION',body:'Опис',data:{spo2:101,observed_at:new Date().toISOString()}}));await c.entry(n1,p.id,{kind:'OBSERVATION',body:'Опис',data:{spo2:98,observed_at:new Date().toISOString()}});await c.entry(therapist,p.id,{kind:'REHAB',body:'Заняття',data:{goals:'Мета',assessment:'Шкала',result:'Результат',next_plan:'Наступне'}});const e=await c.entry(d1,p.id,{kind:'ASSESSMENT',body:'Огляд',data:{diagnosis:'Опис',allergies:'Невідомо',plan:'План'}});await assert.rejects(()=>c.entry(d2,p.id,{kind:'CORRECTION',body:'Чужий запис',corrects_id:e.id}));await c.entry(d1,p.id,{kind:'CORRECTION',body:'Уточнення до запису',corrects_id:e.id});assert.equal((await db.query('SELECT body FROM clinical_entries WHERE id=$1',[e.id])).rows[0].body,'Огляд');});
test('Messages can only be acknowledged by recipient; documents validate type and are audited',async()=>{const p=await patient();const m=await c.message(admin,{patient_id:p.id,recipient_id:d1.id,body:'Спостереження',urgent:true});await assert.rejects(()=>c.readMessage(d2,m.id));await c.readMessage(d1,m.id);assert.ok((await c.messages(d1)).find(x=>x.id===m.id).read_at);await assert.rejects(()=>c.upload(admin,p.id,{filename:'x.pdf',mime:'application/pdf',base64:Buffer.from('<script>').toString('base64')}));const d=await c.upload(admin,p.id,{filename:'test.pdf',mime:'application/pdf',base64:Buffer.from('%PDF-1.4 test fixture').toString('base64')});assert.equal((await c.document(d1,d.id)).filename,'test.pdf');});
test('Staff cannot be double-booked across different cabinets; floors are returned',async()=>{const a=await patient(),b=await patient();const room=await c.room(admin,{room_number:'care-floor',floor:'3'});assert.equal((await c.rooms(admin)).find(r=>r.id===room.id).floor,'3');const first=await c.cabinet(admin,{name:'care-one',type:'gym'}),second=await c.cabinet(admin,{name:'care-two',type:'gym'});const time={starts_at:'2030-02-01T10:00:00Z',ends_at:'2030-02-01T11:00:00Z',staff_id:therapist.id};const results=await Promise.allSettled([c.appointment(admin,{...time,patient_id:a.id,cabinet_id:first.id}),c.appointment(admin,{...time,patient_id:b.id,cabinet_id:second.id})]);assert.equal(results.filter(r=>r.status==='fulfilled').length,1);assert.equal((await c.appointments(therapist,{mine:'true'})).length,1);});

test('Registrar can place patients and book visits but cannot change infrastructure or send messages',async()=>{
 const r=await actor('REGISTRAR','reception-policy');
 const room=await c.room(admin,{room_number:'policy-room',floor:'1'});
 const bed=await c.bed(admin,room.id,{bed_number:'1'});
 const cabinet=await c.cabinet(admin,{name:'policy-cabinet',type:'gym'});
 assert.ok((await c.rooms(r)).some(x=>x.id===room.id));
 const p=await registerTest(r,{name:'Registration policy',bed_id:bed.id});
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
 const worker=await actor('NURSE','close-policy');await approvedShift(worker);
 const p=await patient();const t=await task(p.id);await c.taskAction(worker,t.id,'claim',{});
 await assert.rejects(()=>c.finishWork(worker));assert.equal(await c.onShift(worker),true);
 await c.taskAction(worker,t.id,'release',{});await c.finishWork(worker);assert.equal(await c.onShift(worker),false);
});

test('Login requests approval exactly once and never opens a staff shift without admin',async()=>{
 const password='Automatic-Shift-Test-Password';const hash=await hashPassword(password);
 for(const role of ['REGISTRAR','DOCTOR','NURSE','THERAPIST','ADMIN']){
  const a=await actor(role,'autoshift-'+role.toLowerCase());await db.query('UPDATE users SET password_hash=$1 WHERE id=$2',[hash,a.id]);
  const credentials={login:'care-autoshift-'+role.toLowerCase(),password,device:'test-phone'};
  await c.login(credentials,'127.0.0.1');await c.login(credentials,'127.0.0.1');
  const count=Number((await db.query('SELECT count(*) FROM shifts WHERE user_id=$1 AND ends_at>now()',[a.id])).rows[0].count);
  assert.equal(count,0);const pending=(await c.shiftRequests(admin)).filter(r=>r.user_id===a.id);assert.equal(pending.length,role==='ADMIN'?0:1);if(role!=='ADMIN'){await assert.rejects(()=>c.approveShift(a,pending[0].id,true));await c.approveShift(admin,pending[0].id,true);assert.equal(await c.onShift(a),true);await assert.rejects(()=>c.approveShift(admin,pending[0].id,true));await c.shift(a,{start:false});assert.equal(await c.onShift(a),false);}
 }
 const bad=await actor('NURSE','no-login-shift');await assert.rejects(()=>c.login({login:'care-no-login-shift',password:'wrong'},'127.0.0.1'));assert.equal(await c.onShift(bad),false);
});
test('Notification feed contains only eligible events and no clinical content',async()=>{
 const nurse=await actor('NURSE','notification-nurse'),other=await actor('NURSE','notification-other'),rehab=await actor('THERAPIST','notification-rehab');
 await approvedShift(nurse);await approvedShift(other);await approvedShift(rehab);await approvedShift(d1);await approvedShift(d2);
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
 await approvedShift(n1);await approvedShift(therapist);
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

test('Discharge summary preserves clinician fields and separates admissions',async()=>{
 const p=await patient();
 const summary=await c.entry(d1,p.id,{kind:'DISCHARGE',body:'Підсумок',data:{diagnosis:'Тестовий діагноз',treatment_summary:'Проведене лікування',discharge_condition:'Стан описано',recommendations:'Рекомендації лікаря',follow_up:'Контроль'}});
 assert.equal(summary.data.treatment_summary,'Проведене лікування');
 await assert.rejects(()=>c.entry(n1,p.id,{kind:'DISCHARGE',body:'Не дозволено',data:{recommendations:'',follow_up:''}}));
 await c.discharge(admin,p.id);
 const archived=await c.patient({...d1,permissions:[...d1.permissions,'archive.read']},p.id);
 assert.equal(archived.entries.find((e:any)=>e.id===summary.id).data.diagnosis,'Тестовий діагноз');
});

test('Room retirement protects occupied beds, permissions and historical admissions',async()=>{
 const registrar=await actor('REGISTRAR','retirement-registrar');
 const room=await c.room(admin,{room_number:'Retirement test'});
 const bed=await c.bed(admin,room.id,{bed_number:'1'});
 await assert.rejects(()=>c.removeBed(registrar,bed.id));
 await assert.rejects(()=>c.removeRoom(registrar,room.id));
 await assert.rejects(()=>c.removeRoom(admin,room.id));
 const p=await registerTest(registrar,{name:'Retirement patient',bed_id:bed.id});
 await assert.rejects(()=>c.removeBed(admin,bed.id));
 await c.discharge(registrar,p.id);
 await c.removeBed(admin,bed.id);
 assert.equal((await c.rooms(registrar)).find(r=>r.id===room.id).beds.length,0);
 await assert.rejects(()=>c.byQr(admin,bed.qr_uid));
 await assert.rejects(()=>c.readmit(registrar,p.id,{bed_id:bed.id}));
 await assert.rejects(()=>registerTest(registrar,{name:'Stale bed patient',bed_id:bed.id}));
 const other=await patient();await assert.rejects(()=>c.editPatient(admin,other.id,{name:other.name,doctor_id:d1.id,bed_id:bed.id}));
 await c.removeRoom(admin,room.id);
 await assert.rejects(()=>c.bed(admin,room.id,{bed_number:'2'}));
 assert.equal((await c.rooms(registrar)).some(r=>r.id===room.id),false);
 const history=await c.patient(admin,p.id);
 assert.equal(history.admissions[0].room_number,'Retirement test');
 assert.equal(history.admissions[0].bed_number,'1');
 assert.ok(history.admissions[0].discharged_at);
 const recreated=await c.room(admin,{room_number:'Retirement test'});
 assert.notEqual(recreated.id,room.id);
 const actions=(await db.query('SELECT action FROM audit_events WHERE entity_id=ANY($1::uuid[])',[[room.id,bed.id]])).rows.map(r=>r.action);
 assert.ok(actions.includes('room.deleted'));assert.ok(actions.includes('bed.deleted'));
});

async function registerTest(a:Actor,input:any){return c.register(a,{birth_date:'1990-01-01',complaints:'Тестові скарги',...input});}
async function approvedShift(a:Actor){await c.shift(a,{start:true});const requests=await c.shiftRequests(admin);const pending=requests.find((r:any)=>r.user_id===a.id);if(pending)await c.approveShift(admin,pending.id,true);}
test('Mobile login waits for consent before requesting a shift, then requires approval',async()=>{
 for(const role of ['REGISTRAR','DOCTOR','NURSE','THERAPIST']){
  const user=await c.saveUser(admin,{name:'Mobile '+role,login:'mobile-consent-'+role.toLowerCase(),role,password:'1',specialty:'Тест'});
  const session=await c.login({login:'mobile-consent-'+role.toLowerCase(),password:'1',requestShift:false},'127.0.0.1');
  const a=await c.authenticate(session.token);
  assert.equal((await c.shiftRequests(admin)).some(r=>r.user_id===user.id),false);
  assert.equal((await c.me(a)).onShift,false);
  await c.shift(a,{start:true});await c.shift(a,{start:true});
  const pending=(await c.shiftRequests(admin)).filter(r=>r.user_id===user.id);
  assert.equal(pending.length,1);assert.equal(await c.onShift(a),false);
  await c.approveShift(admin,pending[0].id,true);
  assert.equal((await c.me(a)).onShift,true);
 }
});
test('Registration validates required birth date and complaints and preserves admission details',async()=>{
 await assert.rejects(()=>c.register(admin,{name:'Missing date',complaints:'Скарги'}));
 await assert.rejects(()=>c.register(admin,{name:'Missing complaints',birth_date:'1990-01-01'}));
 const p=await c.register(admin,{name:'Admission detail test',birth_date:'1990-01-01',complaints:'Біль при ходьбі',sex:'FEMALE'});
 const saved=await c.patient(admin,p.id);assert.equal(saved.sex,'FEMALE');assert.equal(saved.admissions[0].complaints,'Біль при ходьбі');
 const room=await c.cabinet(admin,{name:'Ерготерапія тест',type:'Власний тип кабінету'});assert.equal(room.type,'Власний тип кабінету');
 assert.ok(d1.permissions.includes('appointments.manage'));
});
test('Admin can assign a staff one-character password and custom position label',async()=>{
 const saved=await c.saveUser(admin,{name:'Short password staff',login:'short-password-staff',role:'NURSE',role_label:'Старша медсестра',password:'1'});
 const session=await c.login({login:'short-password-staff',password:'1'},'127.0.0.1');assert.equal(session.user.id,saved.id);assert.equal(session.user.role_label,'Старша медсестра');assert.equal(await c.onShift(await c.authenticate(session.token)),false);
 await assert.rejects(()=>c.saveUser(admin,{name:'Weak admin',login:'weak-admin',role:'ADMIN',password:'1'}));
});

test('HTTP mutations require approved shift and rejection keeps it closed',async()=>{
 const created=await c.saveUser(admin,{name:'Approval HTTP',login:'approval-http',role:'REGISTRAR',password:'1'});
 const session=await c.login({login:'approval-http',password:'1'},'127.0.0.1');
 const guard=new AuthGuard(c);const req:any={headers:{authorization:'Bearer '+session.token},method:'POST',path:'/api/patients'};
 const context:any={switchToHttp:()=>({getRequest:()=>req})};
 await assert.rejects(()=>guard.canActivate(context));
 const pending=(await c.shiftRequests(admin)).find(r=>r.user_id===created.id)!;
 await c.approveShift(admin,pending.id,false);await assert.rejects(()=>guard.canActivate(context));
 const worker=await c.authenticate(session.token);await c.shift(worker,{start:true});assert.equal((await c.notificationFeed(worker) as any).pending,true);
 const second=(await c.shiftRequests(admin)).find(r=>r.user_id===created.id)!;await c.approveShift(admin,second.id,true);
 assert.equal(await guard.canActivate(context),true);
 await c.shift(worker,{start:false});await assert.rejects(()=>guard.canActivate(context));
});

test('Patient numbers survive readmission; address and formatted phone find prior treatment; doctor workload counts active admissions',async()=>{
 const p=await registerTest(admin,{name:'Історія '+crypto.randomUUID(),address:'вул. Тестова  145 кв 9',phone:'+380 (96) 123-45-67',doctor_id:d3.id});
 assert.ok(Number(p.patient_number)>0);
 const matches=await c.duplicates(admin,{address:'ВУЛ. тестова 145 кв 9'});
 const found=matches.find(x=>x.id===p.id);assert.equal(found.address_match,true);assert.equal(Number(found.admission_count),1);
 assert.ok((await c.duplicates(admin,{phone:'380961234567'})).some(x=>x.id===p.id));
 const before=Number((await c.staff(d1)).find(x=>x.id===d3.id).patient_count);
 await c.discharge(admin,p.id);assert.equal(Number((await c.staff(d1)).find(x=>x.id===d3.id).patient_count),before-1);
 await c.readmit(admin,p.id,{doctor_id:d3.id,complaints:'Повторне звернення'});
 assert.equal(String((await c.patient(d1,p.id)).patient_number),String(p.patient_number));
 assert.equal(Number((await c.duplicates(admin,{address:'вул. тестова 145 кв 9'})).find(x=>x.id===p.id).admission_count),2);
});
test('Archive contains old cancelled prescriptions with author; staff scope remains enforced; clinical notices link to patient',async()=>{
 const p=await patient(),t=await task(p.id);
 await db.query("UPDATE tasks SET status='CANCELLED',created_at=now()-interval '100 days' WHERE id=$1",[t.id]);
 assert.equal((await c.tasks(d2)).some(x=>x.id===t.id),false);
 const archived=(await c.tasks(d2,{archive:'true'})).find(x=>x.id===t.id);assert.equal(archived.creator,d1.name);
 assert.equal((await c.tasks(therapist,{archive:'true'})).some(x=>x.id===t.id),false);
 assert.ok((await c.messages(d2)).some(x=>x.patient_id===p.id&&x.body.includes(d1.name)&&x.body.includes('призначення')));
 await assert.rejects(()=>c.duplicates(n1,{address:'вул. тестова 145 кв 9'}));
});
test('Home appointments show only assigned specialist with exact schedule and patient',async()=>{
 const p=await patient();const cabinet=await c.cabinet(admin,{name:'Мій розклад '+crypto.randomUUID(),type:'rehab'});
 const ap=await c.appointment(d1,{patient_id:p.id,cabinet_id:cabinet.id,staff_id:therapist.id,starts_at:'2035-06-10T10:00:00Z',ends_at:'2035-06-10T10:30:00Z'});
 const own=(await c.operations(therapist)).appointments.find(x=>x.id===ap.id);assert.equal(own.patient_id,p.id);assert.ok(own.starts_at);assert.ok(own.ends_at);
 assert.equal((await c.operations(d2)).appointments.some(x=>x.id===ap.id),false);
});

test('Prescription retries are atomic, scoped to author, and reject changed payloads',async()=>{
 const p=await patient(),request_id=crypto.randomUUID();
 const input={request_id,patient_id:p.id,description:'Retry test',task_type:'Догляд',scheduled_at:'2031-01-01T10:00:00Z',repeat_count:3};
 const [first,retry]=await Promise.all([c.task(d1,input),c.task(d1,input)]);
 assert.equal(first.id,retry.id);
 assert.equal(Number((await db.query('SELECT count(*) FROM tasks WHERE patient_id=$1',[p.id])).rows[0].count),3);
 await assert.rejects(()=>c.task(d1,{...input,description:'Changed'}),/іншого призначення/);
 const other=await c.task(d2,input);assert.notEqual(other.id,first.id);
 const restarted=new Care(db,'test-secret-with-at-least-thirty-two-characters');
 assert.equal((await restarted.task(d1,input)).id,first.id);
});
